package io.github.joeyblanks.ecommerce.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.notification.support.MailpitClient.Mail;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The whole path with the workers running: event in -> row -> user lookup -> real SMTP -> mail in Mailpit. */
class NotificationFlowIT extends AbstractNotificationIT {

    private UUID customer(String email, String name) {
        UUID id = UUID.randomUUID();
        USERS.put(id, email, name);
        return id;
    }

    @Test
    void theThreeEventsOfAnOrderProduceThreeEmailsToTheCustomer() {
        UUID user = customer("ann@example.com", "Ann Lee");
        UUID order = UUID.randomUUID();
        send("order.placed", placedJson(UUID.randomUUID(), order, user, "Clean Code", "19.99"));
        send("order.paid", paidJson(UUID.randomUUID(), order, user));
        send("order.cancelled", cancelledJson(UUID.randomUUID(), order, user, "CUSTOMER_REQUEST"));

        await(() -> mailbox.to("ann@example.com").size() == 3);

        List<Mail> mails = mailbox.to("ann@example.com");
        String number = order.toString().substring(0, 8).toUpperCase();
        assertThat(mails).extracting(Mail::subject).containsExactlyInAnyOrder(
                "We received your order #" + number, "Payment confirmed for order #" + number,
                "Your order #" + number + " was cancelled");
        Mail placed = mails.stream().filter(m -> m.subject().startsWith("We received")).findFirst().orElseThrow();
        assertThat(placed.text()).contains("Hello Ann Lee,").contains("Clean Code").contains("Total: 19.99");
        assertThat(placed.html()).contains("Clean Code").contains("19.99");
        Mail cancelled = mails.stream().filter(m -> m.subject().contains("cancelled")).findFirst().orElseThrow();
        assertThat(cancelled.text()).contains("You asked us to cancel this order.");
        await(() -> rows("SENT") == 3);
    }

    @Test
    void eachCancellationReasonIsExplainedInPlainLanguage() {
        UUID user = customer("bob@example.com", "Bob");
        for (String reason : List.of("PAYMENT_FAILED", "PAYMENT_TIMEOUT", "SOMETHING_NEW")) {
            send("order.cancelled", cancelledJson(UUID.randomUUID(), UUID.randomUUID(), user, reason));
        }

        await(() -> mailbox.to("bob@example.com").size() == 3);

        String all = String.join("\n", mailbox.to("bob@example.com").stream().map(Mail::text).toList());
        assertThat(all).contains("We could not complete your payment.")
                .contains("We did not receive your payment in time.")
                .contains("The order could not be completed.")
                .doesNotContain("PAYMENT_FAILED").doesNotContain("SOMETHING_NEW");
    }

    @Test
    void theEventOrderDoesNotMatter() {
        UUID user = customer("carol@example.com", "Carol");
        UUID order = UUID.randomUUID();
        send("order.cancelled", cancelledJson(UUID.randomUUID(), order, user, "PAYMENT_FAILED"));
        send("order.paid", paidJson(UUID.randomUUID(), order, user));
        send("order.placed", placedJson(UUID.randomUUID(), order, user, "Book", "5.00"));

        await(() -> mailbox.to("carol@example.com").size() == 3);
    }

    @Test
    void theAddressIsLookedUpWhenSendingAndNeverStored() {
        UUID user = customer("old@example.com", "Dan");
        UUID event = UUID.randomUUID();
        // user-service is unreachable at first, so the notification waits; meanwhile the customer changes address
        USERS.failWith(503);
        send("order.paid", paidJson(event, UUID.randomUUID(), user));
        await(() -> USERS.contactRequests.get() >= 1);
        USERS.put(user, "new@example.com", "Dan");
        USERS.failWith(0);

        await(() -> mailbox.to("new@example.com").size() == 1);

        assertThat(mailbox.to("old@example.com")).isEmpty();
        String stored = jdbc.queryForObject("SELECT payload::text || id::text || coalesce(last_error,'') FROM notifications", String.class);
        assertThat(stored).doesNotContain("example.com");
    }

    @Test
    void theEmailCarriesAStableMessageIdDerivedFromTheEvent() {
        UUID user = customer("erin@example.com", "Erin");
        UUID event = UUID.randomUUID();
        send("order.paid", paidJson(event, UUID.randomUUID(), user));

        await(() -> mailbox.to("erin@example.com").size() == 1);

        assertThat(mailbox.to("erin@example.com").get(0).messageId()).isEqualTo(event + "@notification-service");
    }

    @Test
    void customerSuppliedTextCannotInjectHtmlOrHeaders() {
        UUID user = customer("frank@example.com", "<script>alert(1)</script>");
        send("order.placed", placedJson(UUID.randomUUID(), UUID.randomUUID(), user, "<img src=x onerror=alert(2)>", "9.00"));

        await(() -> mailbox.to("frank@example.com").size() == 1);

        Mail mail = mailbox.to("frank@example.com").get(0);
        assertThat(mail.html()).doesNotContain("<script>").doesNotContain("<img src=x");
        assertThat(mail.html()).contains("&lt;script&gt;");
        assertThat(mail.subject()).doesNotContain("script").doesNotContain("img");
    }

    @Test
    void malformedAndUnknownMessagesAreDeadLetteredAndSendNothing() {
        send("order.placed", "not json");
        send("order.paid", envelope(UUID.randomUUID(), "OrderPaid", "{\"orderId\":\"nope\"}"));
        send("order.cancelled", envelope(UUID.randomUUID(), "SomethingElse", "{}"));

        await(() -> dlqSize() == 3);

        assertThat(rows()).isZero();
        assertThat(mailbox.all()).isEmpty();
    }
}
