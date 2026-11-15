package io.github.joeyblanks.ecommerce.payment.web;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException() {
        super("No payment found for this order");
    }
}
