package io.github.joeyblanks.ecommerce.payment.web;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 9457 problem responses. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidTokenSubjectException.class)
    ProblemDetail badSubject(InvalidTokenSubjectException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseDown(DataAccessResourceFailureException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "Payments are temporarily unavailable; please try again shortly."));
    }
}
