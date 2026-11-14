package io.github.joeyblanks.ecommerce.user.config;

import io.github.joeyblanks.ecommerce.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Creates the first ADMIN at startup, from environment variables only. It is idempotent, never
 * touches an existing account, and never logs the password. Self-registration cannot create admins.
 */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class AdminBootstrapRunner implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final BootstrapAdminProperties properties;
    private final UserService userService;

    public AdminBootstrapRunner(BootstrapAdminProperties properties, UserService userService) {
        this.properties = properties;
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasEmail = properties.email() != null && !properties.email().isBlank();
        boolean hasPassword = properties.password() != null && !properties.password().isBlank();
        if (!hasEmail && !hasPassword) {
            return; // nothing requested
        }
        if (hasEmail != hasPassword) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD must be set together (or both left unset)");
        }
        String email = properties.email().trim();
        if (!email.contains("@")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_EMAIL is not a valid email address");
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH || properties.password().length() > 72) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must be between " + MIN_PASSWORD_LENGTH + " and 72 characters");
        }
        if (userService.createAdminIfAbsent(email, properties.password(), "Administrator")) {
            log.info("Bootstrap admin account created for {}", email);
        } else {
            log.info("Bootstrap admin skipped: an account for {} already exists (left unchanged)", email);
        }
    }
}
