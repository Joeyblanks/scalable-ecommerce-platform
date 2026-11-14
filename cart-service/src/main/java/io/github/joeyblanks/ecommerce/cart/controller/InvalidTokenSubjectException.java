package io.github.joeyblanks.ecommerce.cart.controller;

public class InvalidTokenSubjectException extends RuntimeException {
    public InvalidTokenSubjectException() {
        super("Token subject is not a valid user id");
    }
}
