package io.github.joeyblanks.ecommerce.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.cart.model.CartItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CartItemJsonTest {

    @Test
    void aStoredLineSurvivesAJsonRoundTripExactly() {
        JsonMapper json = JsonMapper.builder().build();
        CartItem item = new CartItem(UUID.randomUUID(), "CC-1", "Clean \"Code\" é ü 日本", new BigDecimal("29.90"), 3,
                Instant.parse("2026-02-01T10:00:00.123456Z"), Instant.parse("2026-02-02T10:00:00Z"));

        CartItem back = json.readValue(json.writeValueAsString(item), CartItem.class);

        assertThat(back.productId()).isEqualTo(item.productId());
        assertThat(back.name()).isEqualTo(item.name());
        assertThat(back.unitPrice()).isEqualByComparingTo("29.90");
        assertThat(back.quantity()).isEqualTo(3);
        assertThat(back.addedAt()).isEqualTo(item.addedAt());
        assertThat(back.updatedAt()).isEqualTo(item.updatedAt());
    }

    @Test
    void keyIsPerUserAndBuiltOnlyFromTheUserId() {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(CartRepository.key(id)).isEqualTo("cart:11111111-2222-3333-4444-555555555555");
    }
}
