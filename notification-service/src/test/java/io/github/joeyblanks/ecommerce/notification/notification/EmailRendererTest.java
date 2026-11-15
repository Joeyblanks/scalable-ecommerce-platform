package io.github.joeyblanks.ecommerce.notification.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.notification.events.OrderEvent;
import io.github.joeyblanks.ecommerce.notification.mail.Email;
import io.github.joeyblanks.ecommerce.notification.user.Contact;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailRendererTest {

    private final EmailRenderer renderer = new EmailRenderer();
    private final UUID order = UUID.fromString("abcdef12-0000-0000-0000-000000000000");
    private final UUID user = UUID.randomUUID();
    private final Instant when = Instant.parse("2026-11-14T10:00:00Z");

    private Contact contact(String name) {
        return new Contact(user, "ann@example.com", name);
    }

    @Test
    void orderReceivedListsItemsAndTotal() {
        var placed = new OrderEvent.Placed(UUID.randomUUID(), order, user, List.of(
                new OrderEvent.Item("Clean Code", 2, new BigDecimal("19.99"), new BigDecimal("39.98")),
                new OrderEvent.Item("Pen", 1, new BigDecimal("1.5"), new BigDecimal("1.50"))), new BigDecimal("41.48"), when);

        Email email = renderer.render(placed, contact("Ann Lee"), "orders@shop.local");

        assertThat(email.from()).isEqualTo("orders@shop.local");
        assertThat(email.to()).isEqualTo("ann@example.com");
        assertThat(email.subject()).isEqualTo("We received your order #ABCDEF12");
        assertThat(email.text()).contains("Hello Ann Lee,").contains("2 x Clean Code  39.98").contains("1 x Pen  1.50")
                .contains("Total: 41.48").contains("2026-11-14 10:00 UTC");
        assertThat(email.html()).contains("Clean Code").contains("41.48");
    }

    @Test
    void paymentConfirmedMentionsTheOrderAndTime() {
        Email email = renderer.render(new OrderEvent.Paid(UUID.randomUUID(), order, user, when), contact("Ann"), "a@b.c");

        assertThat(email.subject()).isEqualTo("Payment confirmed for order #ABCDEF12");
        assertThat(email.text()).contains("payment for order #ABCDEF12 was confirmed on 2026-11-14 10:00 UTC");
    }

    @Test
    void cancelledExplainsEachKnownReasonAndHasASafeFallback() {
        assertThat(EmailRenderer.reasonText("CUSTOMER_REQUEST")).isEqualTo("You asked us to cancel this order.");
        assertThat(EmailRenderer.reasonText("PAYMENT_FAILED")).isEqualTo("We could not complete your payment.");
        assertThat(EmailRenderer.reasonText("PAYMENT_TIMEOUT")).isEqualTo("We did not receive your payment in time.");
        assertThat(EmailRenderer.reasonText("TOTALLY_NEW")).isEqualTo("The order could not be completed.");

        Email email = renderer.render(new OrderEvent.Cancelled(UUID.randomUUID(), order, user, "PAYMENT_TIMEOUT", when), contact("Ann"), "a@b.c");
        assertThat(email.subject()).isEqualTo("Your order #ABCDEF12 was cancelled");
        assertThat(email.text()).contains("Reason: We did not receive your payment in time.").contains("will be refunded");
        assertThat(email.text()).doesNotContain("PAYMENT_TIMEOUT");
    }

    @Test
    void theMessageIdIsStablePerEvent() {
        UUID event = UUID.randomUUID();
        var paid = new OrderEvent.Paid(event, order, user, when);

        assertThat(renderer.render(paid, contact("A"), "a@b.c").messageId()).isEqualTo("<" + event + "@notification-service>");
        assertThat(renderer.render(paid, contact("A"), "a@b.c").messageId()).isEqualTo(renderer.render(paid, contact("B"), "a@b.c").messageId());
    }

    @Test
    void customerSuppliedTextIsEscapedInHtmlAndFlattenedInText() {
        var placed = new OrderEvent.Placed(UUID.randomUUID(), order, user, List.of(
                new OrderEvent.Item("<b>Bold</b> & \"quoted\"", 1, new BigDecimal("1.00"), new BigDecimal("1.00"))), new BigDecimal("1.00"), when);

        Email email = renderer.render(placed, contact("Eve\r\nBcc: spy@evil.test <script>"), "a@b.c");

        assertThat(email.html()).doesNotContain("<script>").doesNotContain("<b>Bold</b>")
                .contains("&lt;b&gt;Bold&lt;/b&gt; &amp; &quot;quoted&quot;").contains("Eve Bcc: spy@evil.test &lt;script&gt;");
        assertThat(email.text()).contains("Hello Eve Bcc: spy@evil.test <script>,").doesNotContain("\r").doesNotContain("Eve\n");
        assertThat(email.subject()).doesNotContain("Eve").doesNotContain("Bold");
    }

    @Test
    void theContactNeverPrintsTheAddress() {
        assertThat(contact("Ann").toString()).doesNotContain("example.com").doesNotContain("Ann");
    }
}
