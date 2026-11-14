package io.github.joeyblanks.ecommerce.catalog.reservation;

import io.github.joeyblanks.ecommerce.catalog.reservation.StockReservationService.Result;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service only (see SecurityConfig and ADR 008): needs a service token with the
 * {@code stock:reserve} scope for this audience. Never reachable with a user or ADMIN token.
 */
@RestController
@RequestMapping("/internal/stock/reservations")
public class StockReservationController {

    private final StockReservationService service;

    public StockReservationController(StockReservationService service) {
        this.service = service;
    }

    /** 201 for a new reservation, 200 when the same reservation id is repeated (idempotent). */
    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal Jwt caller,
                                                       @Valid @RequestBody ReserveRequest request) {
        Result result = service.reserve(request.reservationId(), request.items(), caller.getClaimAsString("client_id"));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.reservation());
    }

    /** Always 200: releasing twice, or releasing an id that was never reserved, is harmless. */
    @PostMapping("/{reservationId}/release")
    public ReservationResponse release(@AuthenticationPrincipal Jwt caller, @PathVariable UUID reservationId) {
        return service.release(reservationId, caller.getClaimAsString("client_id"));
    }
}
