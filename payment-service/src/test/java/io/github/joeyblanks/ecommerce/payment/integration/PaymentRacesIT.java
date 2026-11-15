package io.github.joeyblanks.ecommerce.payment.integration;

import static io.github.joeyblanks.ecommerce.payment.db.Sql.ts;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.payment.jobs.RetentionCleanupJob;
import io.github.joeyblanks.ecommerce.payment.outbox.OutboxRelay;
import io.github.joeyblanks.ecommerce.payment.payment.OrderEventHandler;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentRepository;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentTransactions;
import io.github.joeyblanks.ecommerce.payment.payment.PaymentWorkers;
import io.github.joeyblanks.ecommerce.payment.support.ControlledProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The guarantees under real concurrency. Background workers are switched off (huge intervals) so each
 * test drives the handlers and workers itself and decides exactly when and how often they run.
 */
@TestPropertySource(properties = {
        "app.jobs.outbox-relay-interval=1h", "app.jobs.charge-worker-interval=1h", "app.jobs.refund-worker-interval=1h",
        "app.payments.retry-initial-delay=1ms", "app.payments.retry-max-delay=5ms"})
class PaymentRacesIT extends AbstractPaymentIT {

    @Autowired OrderEventHandler handler;
    @Autowired PaymentWorkers workers;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentRepository repository;
    @Autowired OutboxRelay relay;
    @Autowired RetentionCleanupJob cleanup;
    @Autowired Clock clock;

    private final UUID user = UUID.randomUUID();

    private OrderEventHandler.Outcome place(UUID order, String total) {
        return handler.handle(new OrderEventHandler.Placed(UUID.randomUUID(), order, user, money(total)));
    }

    private OrderEventHandler.Outcome cancel(UUID order) {
        return handler.handle(new OrderEventHandler.Cancelled(UUID.randomUUID(), order));
    }

    private UUID paymentId(UUID order) {
        return jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ?", UUID.class, order);
    }

    /** Runs all tasks at the same instant on separate threads and returns their results. */
    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void drain() {
        for (int i = 0; i < 10; i++) {
            workers.chargeDue();
            workers.refundDue();
        }
    }

    // ------------------------------------------------------------------ never charge twice

    @Test
    void manyConcurrentOrderPlacedEventsForOneOrderRecordOnePayment() throws Exception {
        UUID order = UUID.randomUUID();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> place(order, "70.00")); // different event ids, same order
        }
        concurrently(tasks);

        assertThat(paymentRows()).isEqualTo(1);
        drain();
        assertThat(provider.chargeCalls.get()).isEqualTo(1);
        assertThat(ledgerCharges()).isEqualTo(1);
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    void severalWorkersCompetingForOnePaymentCallTheProviderOnce() throws Exception {
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        ControlledProvider.Gate gate = new ControlledProvider.Gate();
        provider.chargeGate = gate; // the first worker is "in the middle of the call" while the others look

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> first = pool.submit(workers::chargeDue);
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
        List<Callable<Integer>> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            others.add(workers::chargeDue);
        }
        assertThat(concurrently(others)).containsOnly(0); // the lease keeps them away
        gate.release.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        pool.shutdown();

        assertThat(provider.chargeCalls.get()).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo("SUCCEEDED");
    }

    @Test
    void manyWorkersRacingOnManyPaymentsChargeEachOnce() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID o = UUID.randomUUID();
            orders.add(o);
            place(o, "10.00");
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                int total = 0;
                for (int n = 0; n < 5; n++) {
                    total += workers.chargeDue();
                }
                return total;
            });
        }
        assertThat(concurrently(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        assertThat(provider.chargeCalls.get()).isEqualTo(20);
        assertThat(ledgerCharges()).isEqualTo(20);
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(20);
    }

    @Test
    void aCrashedWorkersPaymentIsTakenOverAfterTheLeaseAndStillChargesOnce() {
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        // worker A claims, calls the provider (money moves) and then dies before recording the result
        Instant now = clock.instant();
        PaymentRepository.Claimed claim = repository.claimDueCharges(now, now.plus(Duration.ofMinutes(2)), 10).get(0);
        provider.charge(claim.amount(), claim.currency(), "charge-" + claim.id());
        assertThat(workers.chargeDue()).isZero(); // the lease is still valid: nobody else touches it

        jdbc.update("UPDATE payments SET lease_until = ? WHERE id = ?", ts(now.minusSeconds(1)), claim.id()); // lease expired
        assertThat(workers.chargeDue()).isEqualTo(1);

        assertThat(statusOf(order)).isEqualTo("SUCCEEDED");
        assertThat(ledgerCharges()).isEqualTo(1);  // same idempotency key: the provider returned the first charge
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    void recordingTheResultIsConditionalSoOnlyOneCompletionAnnounces() throws Exception {
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        UUID id = paymentId(order);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> transactions.chargeSucceeded(id, "sim_ch_x")); // e.g. two workers after a lease takeover
        }
        concurrently(tasks);
        transactions.chargeSucceeded(id, "sim_ch_x");
        transactions.chargeDeclined(id, "CARD_DECLINED"); // a late conflicting answer changes nothing either

        assertThat(statusOf(order)).isEqualTo("SUCCEEDED");
        assertThat(outboxCount("PaymentSucceeded")).isEqualTo(1);
        assertThat(outboxCount("PaymentFailed")).isZero();
    }

    @Test
    void severalWorkersCompetingForOneRefundCallTheProviderOnce() throws Exception {
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        workers.chargeDue();
        cancel(order);
        assertThat(statusOf(order)).isEqualTo("REFUND_PENDING");
        ControlledProvider.Gate gate = new ControlledProvider.Gate();
        provider.refundGate = gate;

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> first = pool.submit(workers::refundDue);
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
        List<Callable<Integer>> others = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            others.add(workers::refundDue);
        }
        assertThat(concurrently(others)).containsOnly(0);
        gate.release.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        pool.shutdown();

        assertThat(provider.refundCalls.get()).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo("REFUNDED");
    }

    @Test
    void redeliveringTheSameEventIsRecognisedAsADuplicate() {
        UUID order = UUID.randomUUID();
        var placed = new OrderEventHandler.Placed(UUID.randomUUID(), order, user, money("5.00"));
        assertThat(handler.handle(placed)).isEqualTo(OrderEventHandler.Outcome.APPLIED);
        assertThat(handler.handle(placed)).isEqualTo(OrderEventHandler.Outcome.DUPLICATE);
        var cancelled = new OrderEventHandler.Cancelled(UUID.randomUUID(), order);
        assertThat(handler.handle(cancelled)).isEqualTo(OrderEventHandler.Outcome.APPLIED);
        assertThat(handler.handle(cancelled)).isEqualTo(OrderEventHandler.Outcome.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ cancellation timing

    @Test
    void cancellingBeforeAnyWorkerClaimedItVoidsThePayment() {
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        cancel(order);

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(workers.chargeDue()).isZero();
        assertThat(provider.chargeCalls.get()).isZero();
        assertThat(outboxCount("PaymentSucceeded") + outboxCount("PaymentFailed")).isZero();
    }

    @Test
    void aCancellationRemembersItselfAndSurvivesTheLaterOrderPlaced() {
        UUID order = UUID.randomUUID();
        cancel(order);
        assertThat(statusOf(order)).isEqualTo("CANCELLED");

        place(order, "70.00");
        drain();

        assertThat(statusOf(order)).isEqualTo("CANCELLED");
        assertThat(provider.chargeCalls.get()).isZero();
        assertThat(paymentRows()).isEqualTo(1);
    }

    @Test
    void cancelAndOrderPlacedRacingInEitherOrderNeverCharge() throws Exception {
        for (int round = 0; round < 40; round++) {
            UUID order = UUID.randomUUID();
            List<Callable<Object>> tasks = round % 2 == 0
                    ? List.of(() -> place(order, "33.00"), () -> cancel(order))
                    : List.of(() -> cancel(order), () -> place(order, "33.00"));
            concurrently(tasks);
            drain();
            assertThat(statusOf(order)).as("round %d", round).isIn("CANCELLED", "REFUNDED");
        }
        // Whenever the charge got ahead of the cancellation it was refunded: money in == money out.
        assertThat(ledgerCharges()).isEqualTo(ledgerRefunds());
        assertThat(paymentRows()).isEqualTo(40);
    }

    @Test
    void cancelRacingTheChargeEndsRefundedOrVoidedNeverPaid() throws Exception {
        for (int round = 0; round < 40; round++) {
            UUID order = UUID.randomUUID();
            place(order, "33.00");
            concurrently(List.<Callable<Object>>of(workers::chargeDue, () -> cancel(order)));
            drain();
            assertThat(statusOf(order)).as("round %d", round).isIn("CANCELLED", "REFUNDED");
        }
        assertThat(ledgerCharges()).isEqualTo(ledgerRefunds());
        // PaymentSucceeded was only announced when the charge really completed before the cancellation
        assertThat(outboxCount("PaymentSucceeded")).isLessThanOrEqualTo(ledgerCharges());
    }

    // ------------------------------------------------------------------ unknown outcomes

    @Test
    void aProviderThatIsDownIsRetriedAndNeverReportedAsAFailure() throws Exception {
        provider.alwaysFail = true;
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        for (int i = 0; i < 5; i++) {
            workers.chargeDue();
            Thread.sleep(10);
        }
        assertThat(statusOf(order)).isEqualTo("CHARGING");
        assertThat(provider.chargeCalls.get()).isGreaterThanOrEqualTo(3);
        assertThat(outboxCount("PaymentFailed")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM payments WHERE order_id = ?", String.class, order))
                .contains("provider unreachable");

        provider.alwaysFail = false; // it comes back
        Thread.sleep(20);
        drain();
        assertThat(statusOf(order)).isEqualTo("SUCCEEDED");
        assertThat(ledgerCharges()).isEqualTo(1);
    }

    @Test
    void afterTwoHoursWithoutAnAnswerRetryingStopsAndItIsFlaggedForReview() {
        provider.alwaysFail = true;
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        jdbc.update("UPDATE payments SET created_at = ? WHERE order_id = ?", ts(clock.instant().minus(Duration.ofHours(3))), order);

        workers.chargeDue();

        assertThat(statusOf(order)).isEqualTo("NEEDS_REVIEW");
        int calls = provider.chargeCalls.get();
        assertThat(workers.chargeDue()).isZero(); // no more attempts
        assertThat(provider.chargeCalls.get()).isEqualTo(calls);
        assertThat(outboxCount("PaymentFailed") + outboxCount("PaymentSucceeded")).isZero();
    }

    // ------------------------------------------------------------------ outbox and cleanup

    @Test
    void anEventNobodyIsBoundToStaysInTheOutboxAndIsRetried() {
        admin.deleteQueue(CAPTURE);
        UUID order = UUID.randomUUID();
        place(order, "70.00");
        workers.chargeDue();

        assertThat(relay.publishDueBatch()).isZero(); // unroutable (mandatory): not confirmed, so not published
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND attempts = 1 "
                + "AND last_error IS NOT NULL", Integer.class)).isEqualTo(1);

        jdbc.update("UPDATE outbox_events SET next_attempt_at = ?", ts(clock.instant().minusSeconds(1)));
        admin.declareQueue(new org.springframework.amqp.core.Queue(CAPTURE, true, false, false));
        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(new org.springframework.amqp.core.Queue(CAPTURE))
                .to(new org.springframework.amqp.core.TopicExchange(EXCHANGE)).with("payment.#"));
        assertThat(relay.publishDueBatch()).isEqualTo(1);
        assertThat(rabbit.receive(CAPTURE, 5_000)).isNotNull();
        admin.deleteQueue(CAPTURE);
    }

    @Test
    void retentionDeletesOldPublishedEventsAndOldEventIdsButNeverUnpublishedOnes() {
        Instant now = clock.instant();
        Instant old = now.minus(Duration.ofDays(60));
        UUID oldPublished = insertOutbox(old, old);
        UUID oldUnpublished = insertOutbox(old, null);
        UUID freshPublished = insertOutbox(now, now);
        jdbc.update("INSERT INTO processed_events VALUES (?, 'OrderPlaced', ?)", UUID.randomUUID(), ts(old));
        UUID recentProcessed = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_events VALUES (?, 'OrderPlaced', ?)", recentProcessed, ts(now));

        cleanup.cleanUp();

        List<UUID> remaining = jdbc.query("SELECT id FROM outbox_events", (rs, n) -> rs.getObject(1, UUID.class));
        assertThat(remaining).containsExactlyInAnyOrder(oldUnpublished, freshPublished).doesNotContain(oldPublished);
        assertThat(jdbc.query("SELECT event_id FROM processed_events", (rs, n) -> rs.getObject(1, UUID.class)))
                .containsExactly(recentProcessed);
    }

    private UUID insertOutbox(Instant created, Instant published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (id, aggregate_id, event_type, routing_key, payload, created_at, published_at, next_attempt_at) "
                + "VALUES (?, ?, 'PaymentSucceeded', 'payment.succeeded', '{}'::jsonb, ?, ?, ?)",
                id, UUID.randomUUID(), ts(created), ts(published), ts(created));
        return id;
    }

}
