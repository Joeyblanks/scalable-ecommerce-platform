package io.github.joeyblanks.ecommerce.notification.mail;

/**
 * One email, ready to send. {@code messageId} is stable per notification (derived from the event id), so if
 * the same email is ever sent twice it carries the same Message-ID and a mail client or server can tell.
 */
public record Email(String from, String to, String subject, String text, String html, String messageId) {
}
