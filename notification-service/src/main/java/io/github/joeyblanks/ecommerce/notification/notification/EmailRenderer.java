package io.github.joeyblanks.ecommerce.notification.notification;

import io.github.joeyblanks.ecommerce.notification.events.OrderEvent;
import io.github.joeyblanks.ecommerce.notification.mail.Email;
import io.github.joeyblanks.ecommerce.notification.user.Contact;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the three emails (plain text + HTML). Everything that comes from outside (customer name, product
 * names) is HTML-escaped in the HTML part, and stripped of line breaks in the text part. The subject contains
 * only our own words and the order number, never customer-supplied text.
 */
@Component
public class EmailRenderer {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public Email render(OrderEvent event, Contact contact, String from) {
        String number = orderNumber(event);
        String messageId = "<" + event.eventId() + "@notification-service>";
        String subject;
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder("<html><body style=\"font-family:sans-serif\">");
        String hello = "Hello " + oneLine(contact.fullName()) + ",";
        text.append(hello).append("\n\n");
        html.append("<p>Hello ").append(esc(contact.fullName())).append(",</p>");
        switch (event) {
            case OrderEvent.Placed placed -> {
                subject = "We received your order #" + number;
                text.append("Thank you! We received your order #").append(number).append(" on ")
                        .append(WHEN.format(placed.placedAt())).append(".\n\n");
                html.append("<p>Thank you! We received your order <b>#").append(number).append("</b> on ")
                        .append(WHEN.format(placed.placedAt())).append(".</p><table cellpadding=\"4\">");
                for (OrderEvent.Item item : placed.items()) {
                    text.append("  ").append(item.quantity()).append(" x ").append(oneLine(item.name())).append("  ")
                            .append(money(item.lineTotal())).append('\n');
                    html.append("<tr><td>").append(item.quantity()).append(" &times; ").append(esc(item.name()))
                            .append("</td><td align=\"right\">").append(money(item.lineTotal())).append("</td></tr>");
                }
                text.append("\nTotal: ").append(money(placed.totalAmount())).append("\n\n")
                        .append("We will let you know as soon as your payment is confirmed.\n");
                html.append("<tr><td><b>Total</b></td><td align=\"right\"><b>").append(money(placed.totalAmount()))
                        .append("</b></td></tr></table><p>We will let you know as soon as your payment is confirmed.</p>");
            }
            case OrderEvent.Paid paid -> {
                subject = "Payment confirmed for order #" + number;
                text.append("Your payment for order #").append(number).append(" was confirmed on ")
                        .append(WHEN.format(paid.paidAt())).append(".\nThank you for your purchase!\n");
                html.append("<p>Your payment for order <b>#").append(number).append("</b> was confirmed on ")
                        .append(WHEN.format(paid.paidAt())).append(".</p><p>Thank you for your purchase!</p>");
            }
            case OrderEvent.Cancelled cancelled -> {
                subject = "Your order #" + number + " was cancelled";
                String why = reasonText(cancelled.reason());
                text.append("Your order #").append(number).append(" was cancelled on ")
                        .append(WHEN.format(cancelled.cancelledAt())).append(".\n\nReason: ").append(why)
                        .append("\n\nThe items reserved for you have been released. If a payment was taken, it will be refunded.\n");
                html.append("<p>Your order <b>#").append(number).append("</b> was cancelled on ")
                        .append(WHEN.format(cancelled.cancelledAt())).append(".</p><p><b>Reason:</b> ").append(esc(why))
                        .append("</p><p>The items reserved for you have been released. If a payment was taken, it will be refunded.</p>");
            }
        }
        text.append("\n-- \nThis is an automated message about your order.\n");
        html.append("<p style=\"color:#666\">This is an automated message about your order.</p></body></html>");
        return new Email(from, contact.email(), subject, text.toString(), html.toString(), messageId);
    }

    /** The customer-facing sentence for the contract's reason code. New codes get a safe generic sentence. */
    static String reasonText(String reason) {
        return switch (reason) {
            case "CUSTOMER_REQUEST" -> "You asked us to cancel this order.";
            case "PAYMENT_FAILED" -> "We could not complete your payment.";
            case "PAYMENT_TIMEOUT" -> "We did not receive your payment in time.";
            default -> "The order could not be completed.";
        };
    }

    static String orderNumber(OrderEvent event) {
        return event.orderId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(oneLine(s));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
    }
}
