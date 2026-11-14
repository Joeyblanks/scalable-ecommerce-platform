package io.github.joeyblanks.ecommerce.cart.controller;

import io.github.joeyblanks.ecommerce.cart.dto.AddItemRequest;
import io.github.joeyblanks.ecommerce.cart.dto.CartResponse;
import io.github.joeyblanks.ecommerce.cart.dto.UpdateQuantityRequest;
import io.github.joeyblanks.ecommerce.cart.service.CartService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cart always belongs to the token's subject (the user id). There is deliberately no user id in
 * any URL or body, so one user cannot address another user's cart.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public CartResponse view(@AuthenticationPrincipal Jwt jwt) {
        return service.view(userId(jwt));
    }

    @PostMapping("/items")
    public CartResponse addItem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddItemRequest request) {
        return service.addItem(userId(jwt), request.productId(), request.quantity());
    }

    @PutMapping("/items/{productId}")
    public CartResponse setQuantity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                    @Valid @RequestBody UpdateQuantityRequest request) {
        return service.setQuantity(userId(jwt), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return service.removeItem(userId(jwt), productId);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(@AuthenticationPrincipal Jwt jwt) {
        service.clear(userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidTokenSubjectException();
        }
    }
}
