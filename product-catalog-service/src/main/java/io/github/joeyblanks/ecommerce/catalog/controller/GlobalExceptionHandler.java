package io.github.joeyblanks.ecommerce.catalog.controller;

import io.github.joeyblanks.ecommerce.catalog.service.ConflictException;
import io.github.joeyblanks.ecommerce.catalog.service.InvalidRequestException;
import io.github.joeyblanks.ecommerce.catalog.service.NotFoundException;
import io.github.joeyblanks.ecommerce.catalog.reservation.InsufficientStockException;
import io.github.joeyblanks.ecommerce.catalog.reservation.ReservationConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 9457 problem responses. Extending ResponseEntityExceptionHandler
 * also turns body and parameter validation failures into 400 problem responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("shortages", e.shortages());
        return problem;
    }

    @ExceptionHandler(ReservationConflictException.class)
    ProblemDetail reservationConflict(ReservationConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badArgument(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalid(InvalidRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
