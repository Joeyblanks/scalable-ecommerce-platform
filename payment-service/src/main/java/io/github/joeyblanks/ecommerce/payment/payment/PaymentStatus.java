package io.github.joeyblanks.ecommerce.payment.payment;

/** Internal states of a payment (stored). Customers see the simplified {@link CustomerStatus}. */
public enum PaymentStatus {
    /** Waiting for / in the middle of the provider call. */
    CHARGING,
    SUCCEEDED,
    /** The provider definitively declined. */
    FAILED,
    /** Voided: the order was cancelled and nothing was (or will be) charged. Also the cancel-before-order tombstone. */
    CANCELLED,
    /** Charged, but the order is cancelled: waiting for the refund to be confirmed. */
    REFUND_PENDING,
    REFUNDED,
    /** No definitive provider answer for too long: retrying stopped, a human must look. */
    NEEDS_REVIEW;

    public CustomerStatus forCustomer() {
        return switch (this) {
            case CHARGING, NEEDS_REVIEW -> CustomerStatus.PROCESSING;
            case SUCCEEDED -> CustomerStatus.PAID;
            case FAILED -> CustomerStatus.FAILED;
            case CANCELLED -> CustomerStatus.CANCELLED;
            case REFUND_PENDING -> CustomerStatus.REFUNDING;
            case REFUNDED -> CustomerStatus.REFUNDED;
        };
    }

    public enum CustomerStatus { PROCESSING, PAID, FAILED, CANCELLED, REFUNDING, REFUNDED }
}
