package io.github.joeyblanks.ecommerce.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class ServiceTokenPropertiesTest {

    private static ServiceTokenProperties.Client client(String hash) {
        return new ServiceTokenProperties.Client("order-service", hash, List.of("stock:reserve"), "product-catalog-service");
    }

    private static ServiceTokenProperties props(ServiceTokenProperties.Client... clients) {
        return new ServiceTokenProperties(Duration.ofMinutes(5), List.of(clients));
    }

    @Test
    void aClientIsEnabledOnlyWhenItsSecretHashIsSet() {
        String hash = new BCryptPasswordEncoder(4).encode("s3cret");

        assertThat(props(client(hash)).enabledClients()).hasSize(1);
        assertThat(props(client(""), client(null), client("  ")).enabledClients()).isEmpty();
    }

    @Test
    void aPlainSecretIsRejectedAtStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> props(client("my-plain-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bcrypt")
                .hasMessageNotContaining("my-plain-secret");
    }

    @Test
    void acceptsAllBcryptVariants() {
        String hash = new BCryptPasswordEncoder(4).encode("s3cret"); // $2a$
        for (String prefix : new String[] {"$2a$", "$2b$", "$2y$"}) {
            assertThat(props(client(prefix + hash.substring(4))).enabledClients()).hasSize(1);
        }
    }

    @Test
    void toStringNeverContainsTheHash() {
        String hash = new BCryptPasswordEncoder(4).encode("s3cret");

        assertThat(client(hash).toString()).doesNotContain(hash).contains("order-service");
    }

    @Test
    void noClientsConfiguredIsValid() {
        assertThat(new ServiceTokenProperties(Duration.ofMinutes(5), null).enabledClients()).isEmpty();
    }
}
