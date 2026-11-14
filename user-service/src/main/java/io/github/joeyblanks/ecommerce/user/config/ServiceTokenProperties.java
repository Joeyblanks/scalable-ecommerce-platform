package io.github.joeyblanks.ecommerce.user.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Services allowed to obtain service tokens ({@code app.service-tokens.*}). A client is enabled only
 * when its {@code secretHash} is set. The secret itself is never stored here: only its bcrypt hash.
 */
@Validated
@ConfigurationProperties(prefix = "app.service-tokens")
public record ServiceTokenProperties(@NotNull Duration ttl, @Valid List<Client> clients) {

    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    public ServiceTokenProperties {
        clients = clients == null ? List.of() : List.copyOf(clients);
        for (Client c : clients) {
            if (c.enabled() && !BCRYPT.matcher(c.secretHash()).matches()) {
                throw new IllegalStateException("Service client '" + c.clientId()
                        + "': secret-hash must be a bcrypt hash ($2a$/$2b$/$2y$), never the plain secret. See the README.");
            }
        }
    }

    public List<Client> enabledClients() {
        return clients.stream().filter(Client::enabled).toList();
    }

    public record Client(
            @NotBlank String clientId,
            String secretHash,
            @NotEmpty List<@NotBlank String> scopes,
            @NotBlank String audience) {

        public boolean enabled() {
            return secretHash != null && !secretHash.isBlank();
        }

        @Override
        public String toString() {
            return "Client[clientId=" + clientId + ", scopes=" + scopes + ", audience=" + audience
                    + ", enabled=" + enabled() + "]";
        }
    }
}
