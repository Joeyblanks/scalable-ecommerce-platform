package io.github.joeyblanks.ecommerce.user.dto;

import java.util.UUID;

/** What notification-service may learn about a user: where to send mail and how to greet them. Nothing else. */
public record ContactResponse(UUID userId, String email, String fullName) {
}
