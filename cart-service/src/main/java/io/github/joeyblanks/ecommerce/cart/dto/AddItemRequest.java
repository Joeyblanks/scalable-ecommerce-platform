package io.github.joeyblanks.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Adds {@code quantity} to the cart line of this product (creating the line if needed). */
public record AddItemRequest(@NotNull UUID productId, @Min(1) int quantity) {
}
