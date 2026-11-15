package io.github.joeyblanks.ecommerce.notification.user;

import java.util.UUID;

/** Where to send mail to a user and how to greet them. Looked up per send, never stored. */
public record Contact(UUID userId, String email, String fullName) {
    @Override
    public String toString() {
        return "Contact[userId=" + userId + "]"; // never log the address
    }
}
