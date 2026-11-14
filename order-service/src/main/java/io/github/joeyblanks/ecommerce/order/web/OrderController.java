package io.github.joeyblanks.ecommerce.order.web;

import io.github.joeyblanks.ecommerce.order.order.OrderPlacementService;
import io.github.joeyblanks.ecommerce.order.order.OrderPlacementService.Placement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Orders always belong to the token's subject; no user id appears in any URL or body. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderPlacementService orders;

    public OrderController(OrderPlacementService orders) {
        this.orders = orders;
    }

    /**
     * Places an order from the caller's cart. {@code Idempotency-Key} is required: repeating a request
     * with the same key returns the same order (200) instead of placing a second one.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
                                               @RequestHeader("Idempotency-Key")
                                               @Pattern(regexp = "[A-Za-z0-9_-]{1,100}",
                                                        message = "must be 1-100 letters, digits, '-' or '_'")
                                               String idempotencyKey) {
        Placement placement = orders.place(userId(jwt), jwt.getTokenValue(), idempotencyKey);
        OrderResponse body = OrderResponse.from(placement.order());
        return placement.created()
                ? ResponseEntity.created(URI.create("/api/orders/" + body.id())).body(body)
                : ResponseEntity.status(HttpStatus.OK).body(body);
    }

    @GetMapping
    public PageResponse<OrderResponse> list(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        UUID user = userId(jwt);
        return PageResponse.of(orders.list(user, page, size).stream().map(OrderResponse::from).toList(),
                page, size, orders.count(user));
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return OrderResponse.from(orders.get(orderId, userId(jwt)));
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return OrderResponse.from(orders.cancelByCustomer(orderId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenSubjectException();
        }
    }
}
