package io.github.joeyblanks.ecommerce.order.client;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reads the caller's cart from cart-service, passing on the caller's own token (the cart is theirs). */
@Component
public class CartClient {

    public record Cart(List<Item> items) {
    }

    /** {@code unitPrice} is the cart's display snapshot (ADR 006), never used as the order price. */
    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity) {
    }

    private final RestClient http;

    public CartClient(RestClient.Builder builder, AppProperties.Cart props) {
        this.http = HttpClients.create(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
    }

    public Cart getCart(String userBearerToken) {
        try {
            Cart cart = http.get().uri("/api/cart")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userBearerToken)
                    .retrieve()
                    .body(Cart.class);
            if (cart == null || cart.items() == null) {
                throw new DependencyUnavailableException("cart-service", "Cart service returned no cart", null);
            }
            return cart;
        } catch (RestClientException e) {
            throw new DependencyUnavailableException("cart-service", "Cart service is unavailable", e);
        }
    }
}
