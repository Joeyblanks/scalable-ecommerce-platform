package io.github.joeyblanks.ecommerce.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.notification.jobs.RetentionCleanupJob;
import io.github.joeyblanks.ecommerce.notification.notification.NotificationRecorder;
import io.github.joeyblanks.ecommerce.notification.notification.NotificationWorker;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * "Never the same email twice" and the failure paths, with the scheduled worker switched off so each test
 * drives the worker by hand: duplicates, competing workers, crashes, retries, giving up, retention.
 */
@TestPropertySource(properties = {
        "app.jobs.worker-interval=1h",
        "app.notifications.lease=PT1S",
        "app.notifications.max-attempts=4"
})
class NotificationRacesIT extends AbstractNotificationIT {

    @Autowired NotificationWorker worker;
    @Autowired NotificationRecorder recorder;
    @Autowired RetentionCleanupJob cleanup;

    private UUID customer(String email) {
        UUID id = UUID.randomUUID();
        USERS.put(id, email, "Customer");
        return id;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private void awaitSent(String email, int count) {
        await(() -> mailbox.to(email).size() == count);
    }

    /** Runs the worker until nothing is due any more (retries are due after ~100-400 ms). */
    private void drain() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            worker.sendDue();
            if (rows("PENDING") == 0) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    void theSameEventDeliveredManyTimesAtOnceRecordsAndSendsOneEmail() throws Exception {
        UUID user = customer("a@example.com");
        UUID event = UUID.randomUUID();
        byte[] message = bytes(paidJson(event, UUID.randomUUID(), user));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<NotificationRecorder.Outcome>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return recorder.record(message);
            }));
        }
        go.countDown();
        int recorded = 0;
        for (Future<NotificationRecorder.Outcome> f : results) {
            if (f.get() == NotificationRecorder.Outcome.RECORDED) {
                recorded++;
            }
        }
        pool.shutdown();
        drain();

        assertThat(recorded).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        awaitSent("a@example.com", 1);
        assertThat(sender.calls.get()).isEqualTo(1);
    }

    @Test
    void anEventRedeliveredAfterItWasSentSendsNothingMore() throws Exception {
        UUID user = customer("b@example.com");
        byte[] message = bytes(paidJson(UUID.randomUUID(), UUID.randomUUID(), user));
        recorder.record(message);
        drain();
        awaitSent("b@example.com", 1);

        for (int i = 0; i < 3; i++) {
            assertThat(recorder.record(message)).isEqualTo(NotificationRecorder.Outcome.DUPLICATE);
        }
        drain();

        assertThat(sender.calls.get()).isEqualTo(1);
        assertThat(mailbox.to("b@example.com")).hasSize(1);
    }

    @Test
    void severalWorkersCompetingSendEachNotificationOnce() throws Exception {
        UUID user = customer("c@example.com");
        for (int i = 0; i < 12; i++) {
            recorder.record(bytes(paidJson(UUID.randomUUID(), UUID.randomUUID(), user)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            runs.add(pool.submit(() -> {
                go.await();
                for (int n = 0; n < 5; n++) {
                    worker.sendDue();
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : runs) {
            f.get();
        }
        pool.shutdown();

        awaitSent("c@example.com", 12);
        assertThat(sender.calls.get()).isEqualTo(12);
        assertThat(rows("SENT")).isEqualTo(12);
        assertThat(sender.messageIds).doesNotHaveDuplicates();
    }

    @Test
    void aCrashedWorkersNotificationIsTakenOverAfterTheLeaseAndSentOnce() throws Exception {
        UUID user = customer("d@example.com");
        UUID event = UUID.randomUUID();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));
        // a worker claims it and "dies" before sending: the row stays leased
        jdbc.update("UPDATE notifications SET lease_until = now() + interval '1 second', attempts = 1");

        assertThat(worker.sendDue()).as("leased: not available").isZero();
        Thread.sleep(1200);
        assertThat(worker.sendDue()).as("lease expired: taken over").isEqualTo(1);

        awaitSent("d@example.com", 1);
        assertThat(sender.calls.get()).isEqualTo(1);
        assertThat(statusOf(event)).isEqualTo("SENT");
    }

    @Test
    void userServiceBeingDownDelaysTheEmailAndNeverLosesItOrSendsItTwice() throws Exception {
        UUID user = customer("e@example.com");
        UUID event = UUID.randomUUID();
        USERS.failWith(503);
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));

        for (int i = 0; i < 4; i++) {
            worker.sendDue();
            Thread.sleep(150);
        }
        assertThat(statusOf(event)).isEqualTo("PENDING");
        assertThat(mailbox.all()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isGreaterThan(1);

        USERS.failWith(0);
        drain();

        awaitSent("e@example.com", 1);
        assertThat(statusOf(event)).isEqualTo("SENT");
        assertThat(sender.calls.get()).isEqualTo(1);
    }

    @Test
    void aFailingMailServerIsRetriedAndTheEmailArrivesOnce() throws Exception {
        UUID user = customer("f@example.com");
        UUID event = UUID.randomUUID();
        sender.failNext(2);
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));

        drain();

        awaitSent("f@example.com", 1);
        assertThat(sender.calls.get()).isEqualTo(3);
        assertThat(statusOf(event)).isEqualTo("SENT");
    }

    @Test
    void anUnknownUserIsFailedAtOnceWithoutRetrying() throws Exception {
        UUID ghost = UUID.randomUUID(); // never registered
        UUID event = UUID.randomUUID();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), ghost)));

        worker.sendDue();
        worker.sendDue();

        assertThat(statusOf(event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM notifications", String.class)).contains("does not exist");
        assertThat(sender.calls.get()).isZero();
    }

    @Test
    void afterTheMaximumAttemptsItIsFailedAndNeverTriedAgain() throws Exception {
        UUID user = customer("g@example.com");
        UUID event = UUID.randomUUID();
        sender.alwaysFail();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));

        drain();

        assertThat(statusOf(event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isEqualTo(4);
        int calls = sender.calls.get();
        Thread.sleep(500);
        worker.sendDue();
        assertThat(sender.calls.get()).as("FAILED is final").isEqualTo(calls);
        assertThat(mailbox.all()).isEmpty();
    }

    @Test
    void aNotificationOlderThanTheGiveUpTimeIsFailedOnItsNextFailure() throws Exception {
        UUID user = customer("h@example.com");
        UUID event = UUID.randomUUID();
        sender.alwaysFail();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));
        jdbc.update("UPDATE notifications SET created_at = now() - interval '2 hours'");

        worker.sendDue();

        assertThat(statusOf(event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notifications", Integer.class)).isEqualTo(1);
    }

    @Test
    void anEmailThatWasDeliveredButWhoseResponseWasLostIsTheOneAcceptedRareDuplicate() throws Exception {
        // Documents the honest limit (ADR 010): SMTP has no idempotency. The retry sends again, with the SAME
        // Message-ID, so the duplicate is recognisable. It is chosen over ever losing an email.
        UUID user = customer("i@example.com");
        UUID event = UUID.randomUUID();
        sender.deliverButReportFailureOnce();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), user)));

        drain();

        awaitSent("i@example.com", 2);
        assertThat(mailbox.to("i@example.com")).extracting(m -> m.messageId()).containsOnly(event + "@notification-service");
        assertThat(statusOf(event)).isEqualTo("SENT");
    }

    @Test
    void completionIsConditionalSoOnlyOneCallMarksItSent() {
        UUID event = UUID.randomUUID();
        recorder.record(bytes(paidJson(event, UUID.randomUUID(), UUID.randomUUID())));
        UUID id = jdbc.queryForObject("SELECT id FROM notifications", UUID.class);
        var repository = new io.github.joeyblanks.ecommerce.notification.notification.NotificationRepository(jdbc);

        assertThat(repository.markSent(id, Instant.now())).isTrue();
        assertThat(repository.markSent(id, Instant.now())).isFalse();
        assertThat(repository.markFailed(id, "late", Instant.now())).as("SENT is final").isFalse();
        assertThat(repository.scheduleRetry(id, "late", Instant.now(), Instant.now())).isFalse();
    }

    @Test
    void retentionDeletesOldSentRowsButNeverPendingOrFailedOnes() throws Exception {
        UUID user = customer("j@example.com");
        UUID oldSent = UUID.randomUUID();
        UUID recentSent = UUID.randomUUID();
        UUID oldPending = UUID.randomUUID();
        UUID oldFailed = UUID.randomUUID();
        for (UUID e : List.of(oldSent, recentSent, oldPending, oldFailed)) {
            recorder.record(bytes(paidJson(e, UUID.randomUUID(), user)));
        }
        jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now() - interval '40 days' WHERE event_id = ?", oldSent);
        jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now() - interval '1 day' WHERE event_id = ?", recentSent);
        // even with an old sent_at (as if left over from an earlier state), only SENT rows may be deleted
        jdbc.update("UPDATE notifications SET created_at = now() - interval '90 days', sent_at = now() - interval '90 days' WHERE event_id = ?", oldPending);
        jdbc.update("UPDATE notifications SET status = 'FAILED', created_at = now() - interval '90 days', sent_at = now() - interval '90 days' WHERE event_id = ?", oldFailed);

        cleanup.cleanUp();

        assertThat(statusOf(oldSent)).isNull();
        assertThat(statusOf(recentSent)).isEqualTo("SENT");
        assertThat(statusOf(oldPending)).isEqualTo("PENDING");
        assertThat(statusOf(oldFailed)).isEqualTo("FAILED");
    }
}
