package io.github.joeyblanks.ecommerce.catalog.dto;

import io.github.joeyblanks.ecommerce.catalog.model.Product;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, String description, BigDecimal price,
                              int stockQuantity, UUID categoryId, String categoryName,
                              Instant createdAt, Instant updatedAt) {

    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                p.getStockQuantity(), p.getCategory().getId(), p.getCategory().getName(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
