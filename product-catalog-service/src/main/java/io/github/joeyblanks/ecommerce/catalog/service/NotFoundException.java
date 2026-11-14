package io.github.joeyblanks.ecommerce.catalog.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
