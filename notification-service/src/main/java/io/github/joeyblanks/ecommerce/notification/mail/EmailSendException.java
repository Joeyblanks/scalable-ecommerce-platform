package io.github.joeyblanks.ecommerce.notification.mail;

/** Sending failed or its outcome is unknown. The message never contains the address, the body or the API key. */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
