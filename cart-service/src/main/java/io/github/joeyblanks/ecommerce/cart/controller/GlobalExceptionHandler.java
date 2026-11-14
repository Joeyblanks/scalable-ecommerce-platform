package io.github.joeyblanks.ecommerce.cart.controller;

import io.github.joeyblanks.ecommerce.cart.catalog.CatalogUnavailableException;
import io.github.joeyblanks.ecommerce.cart.catalog.ProductNotFoundException;
import io.github.joeyblanks.ecommerce.cart.repository.CartStorageUnavailableException;
import io.github.joeyblanks.ecommerce.cart.service.CartBusyException;
import io.github.joeyblanks.ecommerce.cart.service.CartLimitException;
import io.github.joeyblanks.ecommerce.cart.service.InsufficientStockException;
import io.github.joeyblanks.ecommerce.cart.service.InvalidRequestException;
import io.github.joeyblanks.ecommerce.cart.service.ItemNotInCartException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps failures to RFC 9457 problem responses. Extending ResponseEntityExceptionHandler also turns
 * body and parameter validation failures into 400 problem responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler({ProductNotFoundException.class, ItemNotInCartException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("availableStock", e.availableStock());
        problem.setProperty("requestedTotal", e.requestedTotal());
        return problem;
    }

    @ExceptionHandler({CartLimitException.class, CartBusyException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidTokenSubjectException.class)
    ProblemDetail badSubject(InvalidTokenSubjectException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<ProblemDetail> catalogUnavailable(CatalogUnavailableException e) {
        return unavailable("The product catalog is temporarily unavailable, so the cart could not be changed. "
                + "Your cart is unchanged; please try again shortly.");
    }

    @ExceptionHandler(CartStorageUnavailableException.class)
    ResponseEntity<ProblemDetail> storageUnavailable(CartStorageUnavailableException e) {
        return unavailable("Cart storage is temporarily unavailable; please try again shortly.");
    }

    private static ResponseEntity<ProblemDetail> unavailable(String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail));
    }
}
