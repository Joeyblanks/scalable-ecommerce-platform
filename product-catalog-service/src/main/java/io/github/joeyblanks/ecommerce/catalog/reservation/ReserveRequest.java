package io.github.joeyblanks.ecommerce.catalog.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Reserve all of these or nothing. {@code reservationId} is chosen by the caller (the order id). */
public record ReserveRequest(
        @NotNull UUID reservationId,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Item> items) {

    public record Item(@NotNull UUID productId, @Min(1) @Max(10_000) int quantity) {
    }
}
