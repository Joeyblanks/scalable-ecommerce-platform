package io.github.joeyblanks.ecommerce.catalog.service;

public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
