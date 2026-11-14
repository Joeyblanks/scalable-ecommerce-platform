package io.github.joeyblanks.ecommerce.user.dto;

import io.github.joeyblanks.ecommerce.user.model.User;
import java.time.Instant;
import java.util.UUID;

/** Public view of a user. Deliberately has no password field. */
public record ProfileResponse(UUID id, String email, String fullName, Instant createdAt) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getId(), user.getEmail(), user.getFullName(), user.getCreatedAt());
    }
}
