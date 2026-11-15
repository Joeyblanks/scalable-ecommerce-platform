package io.github.joeyblanks.ecommerce.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.user.repository.UserRepository;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The internal contact endpoint that notification-service uses to turn a userId into an email address,
 * with real tokens from the real token endpoint. Run by Failsafe; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ContactEndpointIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static final String NOTIFY_SECRET = "notification-test-secret";
    static final String ORDER_SECRET = "order-service-test-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        registry.add("app.jwt.private-key", TestKeys::sharedBase64Pem);
        registry.add("app.jwt.issuer", () -> "user-service");
        registry.add("app.service-tokens.clients[0].client-id", () -> "notification-service");
        registry.add("app.service-tokens.clients[0].secret-hash", () -> bcrypt.encode(NOTIFY_SECRET));
        registry.add("app.service-tokens.clients[0].scopes[0]", () -> "users:read-contact");
        registry.add("app.service-tokens.clients[0].audience", () -> "user-service");
        registry.add("app.service-tokens.clients[1].client-id", () -> "order-service");
        registry.add("app.service-tokens.clients[1].secret-hash", () -> bcrypt.encode(ORDER_SECRET));
        registry.add("app.service-tokens.clients[1].scopes[0]", () -> "stock:reserve");
        registry.add("app.service-tokens.clients[1].audience", () -> "product-catalog-service");
        // same client id as the real config, but a token for another audience: must not work here
        registry.add("app.service-tokens.clients[2].client-id", () -> "misaudienced");
        registry.add("app.service-tokens.clients[2].secret-hash", () -> bcrypt.encode("m"));
        registry.add("app.service-tokens.clients[2].scopes[0]", () -> "users:read-contact");
        registry.add("app.service-tokens.clients[2].audience", () -> "somewhere-else");
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    private UUID register(String email, String name) throws Exception {
        String body = mvc.perform(post("/api/users/register").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"fullName\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String customerToken(String email) throws Exception {
        String body = mvc.perform(post("/api/users/login").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String serviceToken(String clientId, String secret, String scope) throws Exception {
        var req = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").with(httpBasic(clientId, secret));
        if (scope != null) {
            req = req.param("scope", scope);
        }
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    @Test
    void notificationServiceGetsTheEmailAndNameAndNothingElse() throws Exception {
        UUID id = register("Ann@Example.com", "Ann Lee");
        String token = serviceToken("notification-service", NOTIFY_SECRET, "users:read-contact");

        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(id.toString()))
                .andExpect(jsonPath("$.email").value("ann@example.com"))
                .andExpect(jsonPath("$.fullName").value("Ann Lee"))
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void anUnknownUserIs404AndABadIdIs400() throws Exception {
        String auth = "Bearer " + serviceToken("notification-service", NOTIFY_SECRET, "users:read-contact");

        mvc.perform(get("/api/internal/users/{id}/contact", UUID.randomUUID()).header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/internal/users/not-a-uuid/contact").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noTokenIs401() throws Exception {
        UUID id = register("bob@example.com", "Bob");

        mvc.perform(get("/api/internal/users/{id}/contact", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customersCannotReadAnyonesContactDetailsNotEvenTheirOwn() throws Exception {
        UUID id = register("carol@example.com", "Carol");
        String customer = customerToken("carol@example.com");

        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherServicesAndTokensWithoutTheScopeOrAudienceAreRefused() throws Exception {
        UUID id = register("dave@example.com", "Dave");

        // another service, valid token, wrong scope and audience
        String orderToken = serviceToken("order-service", ORDER_SECRET, "stock:reserve");
        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer " + orderToken))
                .andExpect(status().isForbidden());
        // the right client, but it did not ask for the scope: scopes are never granted implicitly
        String noScope = serviceToken("notification-service", NOTIFY_SECRET, null);
        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer " + noScope))
                .andExpect(status().isForbidden());
        // the right scope, but a token meant for another audience
        String otherAudience = serviceToken("misaudienced", "m", "users:read-contact");
        mvc.perform(get("/api/internal/users/{id}/contact", id).header("Authorization", "Bearer " + otherAudience))
                .andExpect(status().isForbidden());
    }

    @Test
    void thereIsNoWayToListOrWriteViaTheInternalPath() throws Exception {
        String auth = "Bearer " + serviceToken("notification-service", NOTIFY_SECRET, "users:read-contact");
        UUID id = register("erin@example.com", "Erin");

        mvc.perform(get("/api/internal/users").header("Authorization", auth)).andExpect(status().isForbidden());
        mvc.perform(post("/api/internal/users/{id}/contact", id).header("Authorization", auth)).andExpect(status().isForbidden());
        // and the service token is still refused on the customer endpoints
        mvc.perform(get("/api/users/me").header("Authorization", auth)).andExpect(status().isForbidden());
        assertThat(users.count()).isEqualTo(1);
    }
}
