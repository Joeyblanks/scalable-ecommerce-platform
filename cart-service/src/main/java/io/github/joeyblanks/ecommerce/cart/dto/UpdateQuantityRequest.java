package io.github.joeyblanks.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;

/** Sets the line to exactly this quantity. To remove a line, use DELETE. */
public record UpdateQuantityRequest(@Min(1) int quantity) {
}
