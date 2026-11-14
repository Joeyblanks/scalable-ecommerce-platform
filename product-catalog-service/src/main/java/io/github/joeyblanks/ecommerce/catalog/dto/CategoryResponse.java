package io.github.joeyblanks.ecommerce.catalog.dto;

import io.github.joeyblanks.ecommerce.catalog.model.Category;
import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(UUID id, String name, String description, Instant createdAt) {

    public static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getDescription(), c.getCreatedAt());
    }
}
