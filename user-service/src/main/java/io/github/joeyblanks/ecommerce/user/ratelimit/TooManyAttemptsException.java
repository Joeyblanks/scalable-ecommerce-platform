package io.github.joeyblanks.ecommerce.user.ratelimit;

import java.time.Duration;

/** Too many attempts for this key; try again after {@link #retryAfter()}. */
public class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** Whole seconds for the Retry-After header, at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
