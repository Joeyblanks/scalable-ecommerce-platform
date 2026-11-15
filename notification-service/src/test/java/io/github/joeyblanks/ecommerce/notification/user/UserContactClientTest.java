package io.github.joeyblanks.ecommerce.notification.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import io.github.joeyblanks.ecommerce.notification.support.FakeUserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class UserContactClientTest {

    private FakeUserService users;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-15T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeEach
    void start() {
        users = new FakeUserService();
    }

    @AfterEach
    void stop() {
        users.close();
    }

    private UserContactClient client(String secret) {
        AppProperties.UserService props = new AppProperties.UserService(users.baseUrl(), users.baseUrl() + "/oauth2/token",
                FakeUserService.CLIENT_ID, secret, FakeUserService.SCOPE, Duration.ofSeconds(1), Duration.ofSeconds(2));
        return new UserContactClient(RestClient.builder(), props, new ServiceTokenClient(RestClient.builder(), props, clock));
    }

    @Test
    void returnsTheContactUsingATokenRequestedWithTheScope() {
        UUID id = UUID.randomUUID();
        users.put(id, "ann@example.com", "Ann Lee");

        Contact contact = client(FakeUserService.CLIENT_SECRET).contactOf(id);

        assertThat(contact.userId()).isEqualTo(id);
        assertThat(contact.email()).isEqualTo("ann@example.com");
        assertThat(contact.fullName()).isEqualTo("Ann Lee");
        assertThat(users.tokenScopes).containsExactly("users:read-contact"); // scopes are never implicit (ADR 008)
    }

    @Test
    void theTokenIsCachedUntilShortlyBeforeItExpiresThenRefreshed() {
        UUID id = UUID.randomUUID();
        users.put(id, "ann@example.com", "Ann");
        UserContactClient client = client(FakeUserService.CLIENT_SECRET);

        client.contactOf(id);
        client.contactOf(id);
        client.contactOf(id);
        assertThat(users.tokenRequests.get()).as("one token for many lookups").isEqualTo(1);

        now.set(now.get().plusSeconds(280)); // 20 s left of 300: inside the 30 s refresh margin
        client.contactOf(id);
        assertThat(users.tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void anUnknownUserIsPermanent() {
        assertThatThrownBy(() -> client(FakeUserService.CLIENT_SECRET).contactOf(UUID.randomUUID()))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void everythingElseIsTemporary() {
        UUID id = UUID.randomUUID();
        users.put(id, "ann@example.com", "Ann");
        UserContactClient client = client(FakeUserService.CLIENT_SECRET);

        for (int status : new int[]{500, 503, 429, 400}) {
            users.failWith(status);
            assertThatThrownBy(() -> client.contactOf(id)).as("http %s", status).isInstanceOf(ContactUnavailableException.class);
        }
        users.failWith(0);
        assertThat(client.contactOf(id).email()).isEqualTo("ann@example.com");
    }

    @Test
    void aRejectedTokenIsDroppedSoTheNextAttemptGetsAFreshOne() {
        UUID id = UUID.randomUUID();
        users.put(id, "ann@example.com", "Ann");
        UserContactClient client = client(FakeUserService.CLIENT_SECRET);
        client.contactOf(id);

        users.failWith(401);
        assertThatThrownBy(() -> client.contactOf(id)).isInstanceOf(ContactUnavailableException.class);
        users.failWith(0);
        client.contactOf(id);

        assertThat(users.tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void aWrongOrMissingSecretIsTemporaryNotAnUnknownUser() {
        UUID id = UUID.randomUUID();
        users.put(id, "ann@example.com", "Ann");

        assertThatThrownBy(() -> client("wrong").contactOf(id)).isInstanceOf(ContactUnavailableException.class);
        assertThatThrownBy(() -> client("").contactOf(id)).isInstanceOf(ContactUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void aContactWithoutAnAddressIsTemporaryNotSomethingToSendTo() {
        UUID id = UUID.randomUUID();
        users.put(id, "", "Ann");

        assertThatThrownBy(() -> client(FakeUserService.CLIENT_SECRET).contactOf(id))
                .isInstanceOf(ContactUnavailableException.class).hasMessageContaining("no email");
    }

    @Test
    void anUnreachableUserServiceIsTemporary() {
        UUID id = UUID.randomUUID();
        users.close();

        assertThatThrownBy(() -> client(FakeUserService.CLIENT_SECRET).contactOf(id)).isInstanceOf(ContactUnavailableException.class);
    }

    @Test
    void theSettingsNeverPrintTheSecret() {
        AppProperties.UserService props = new AppProperties.UserService("http://x", "http://x/t", "id", "s3cret", "scope",
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(props.toString()).doesNotContain("s3cret");
    }
}
