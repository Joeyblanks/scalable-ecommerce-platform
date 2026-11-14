package io.github.joeyblanks.ecommerce.cart.service;

public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
