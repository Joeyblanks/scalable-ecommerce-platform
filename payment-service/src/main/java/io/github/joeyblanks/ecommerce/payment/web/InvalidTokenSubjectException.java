package io.github.joeyblanks.ecommerce.payment.web;

public class InvalidTokenSubjectException extends RuntimeException {
    public InvalidTokenSubjectException() {
        super("Token subject is not a valid user id");
    }
}
