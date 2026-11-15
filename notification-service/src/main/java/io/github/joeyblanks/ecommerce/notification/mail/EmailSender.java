package io.github.joeyblanks.ecommerce.notification.mail;

/**
 * Sends one email. Throws on any failure (the caller retries): there is no way to know whether a message that
 * failed with a timeout was delivered, which is why a rare duplicate is possible and a lost email is not (ADR 010).
 */
public interface EmailSender {

    /** Short name for logs, e.g. "smtp". */
    String name();

    void send(Email email);
}
