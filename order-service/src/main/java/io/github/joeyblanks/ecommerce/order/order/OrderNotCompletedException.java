package io.github.joeyblanks.ecommerce.order.order;

/**
 * The outcome of the attempt could not be confirmed (a storage error while saving, possibly after the
 * save succeeded, or the reconciler gave up on it). Retrying with the same Idempotency-Key returns the
 * order if it did go through; otherwise the attempt failed and its stock is being returned.
 */
public class OrderNotCompletedException extends RuntimeException {
    public OrderNotCompletedException(Throwable cause) {
        super("We could not confirm your order. Please retry with the same Idempotency-Key: if the order went "
                + "through you will get it back, otherwise nothing was ordered and any reserved stock is returned.", cause);
    }
}
