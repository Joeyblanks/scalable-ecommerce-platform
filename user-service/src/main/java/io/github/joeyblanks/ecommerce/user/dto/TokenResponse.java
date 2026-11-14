package io.github.joeyblanks.ecommerce.user.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
