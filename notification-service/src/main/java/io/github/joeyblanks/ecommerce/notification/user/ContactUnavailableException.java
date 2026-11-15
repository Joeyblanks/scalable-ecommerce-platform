package io.github.joeyblanks.ecommerce.notification.user;

/** user-service (or its token endpoint) could not answer right now: the send is retried later. */
public class ContactUnavailableException extends RuntimeException {

    public ContactUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
