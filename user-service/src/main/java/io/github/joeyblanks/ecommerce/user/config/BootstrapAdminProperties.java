package io.github.joeyblanks.ecommerce.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional first-admin credentials, from {@code BOOTSTRAP_ADMIN_EMAIL} / {@code BOOTSTRAP_ADMIN_PASSWORD}.
 * There is no default password: if unset, no admin is created.
 */
@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record BootstrapAdminProperties(String email, String password) {

    @Override
    public String toString() {
        return "BootstrapAdminProperties[email=" + email + ", password=***]";
    }
}
