package io.github.joeyblanks.ecommerce.catalog.service;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
