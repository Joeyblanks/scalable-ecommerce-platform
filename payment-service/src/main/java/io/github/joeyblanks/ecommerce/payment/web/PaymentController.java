package io.github.joeyblanks.ecommerce.payment.web;

import io.github.joeyblanks.ecommerce.payment.payment.PaymentRepository;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer sees the payment status of their own orders only: the user comes from the token, never the URL. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    /** 404 for an unknown order, someone else's order, or an order whose payment is not recorded yet. */
    @GetMapping("/orders/{orderId}")
    public PaymentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return payments.findForUser(orderId, userId(jwt)).map(PaymentResponse::from).orElseThrow(PaymentNotFoundException::new);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenSubjectException();
        }
    }
}
