package io.github.joeyblanks.ecommerce.user.controller;

import io.github.joeyblanks.ecommerce.user.dto.ContactResponse;
import io.github.joeyblanks.ecommerce.user.service.UserService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service only (see SecurityConfig and ADR 008): reachable with a service token that has the
 * scope {@code users:read-contact} and audience {@code user-service}, and with nothing else.
 */
@RestController
@RequestMapping("/api/internal/users")
public class InternalUserController {

    private final UserService userService;

    public InternalUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{id}/contact")
    public ContactResponse contact(@PathVariable UUID id) {
        // Never cached: Spring Security's default response headers add Cache-Control: no-store (asserted in the IT).
        return userService.getContact(id);
    }
}
