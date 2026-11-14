package io.github.joeyblanks.ecommerce.order.web;

import io.github.joeyblanks.ecommerce.order.client.DependencyUnavailableException;
import io.github.joeyblanks.ecommerce.order.client.OutOfStockException;
import io.github.joeyblanks.ecommerce.order.order.EmptyCartException;
import io.github.joeyblanks.ecommerce.order.order.OrderInProgressException;
import io.github.joeyblanks.ecommerce.order.order.OrderNotCancellableException;
import io.github.joeyblanks.ecommerce.order.order.OrderNotCompletedException;
import io.github.joeyblanks.ecommerce.order.order.OrderNotFoundException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem responses. Validation failures (incl. a missing Idempotency-Key) become 400. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EmptyCartException.class)
    ProblemDetail emptyCart(EmptyCartException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(OutOfStockException.class)
    ProblemDetail outOfStock(OutOfStockException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                e.getMessage() + ". Your cart is unchanged; adjust it and try again.");
        problem.setProperty("shortages", e.shortages());
        return problem;
    }

    @ExceptionHandler({OrderInProgressException.class, OrderNotCancellableException.class})
    ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidTokenSubjectException.class)
    ProblemDetail badSubject(InvalidTokenSubjectException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<ProblemDetail> dependencyDown(DependencyUnavailableException e) {
        return unavailable("A service needed to place your order (" + e.dependency() + ") is temporarily unavailable. "
                + "Nothing was ordered and your cart is unchanged; please try again shortly.");
    }

    @ExceptionHandler(OrderNotCompletedException.class)
    ResponseEntity<ProblemDetail> notCompleted(OrderNotCompletedException e) {
        return unavailable(e.getMessage());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseDown(DataAccessResourceFailureException e) {
        return unavailable("Orders are temporarily unavailable; please try again shortly.");
    }

    private static ResponseEntity<ProblemDetail> unavailable(String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail));
    }
}
