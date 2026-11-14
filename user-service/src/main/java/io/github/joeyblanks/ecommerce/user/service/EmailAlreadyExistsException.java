package io.github.joeyblanks.ecommerce.user.service;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("An account with this email already exists");
    }
}
