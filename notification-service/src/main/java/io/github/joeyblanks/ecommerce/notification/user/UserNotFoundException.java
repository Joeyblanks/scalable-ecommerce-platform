package io.github.joeyblanks.ecommerce.notification.user;

import java.util.UUID;

/** user-service says this user does not exist: no address can ever be found, so retrying is pointless. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("User " + userId + " does not exist (anymore)");
    }
}
