package io.github.joeyblanks.ecommerce.catalog.reservation;

/** The reservation id was already released, or was already used for different items. */
public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) {
        super(message);
    }
}
