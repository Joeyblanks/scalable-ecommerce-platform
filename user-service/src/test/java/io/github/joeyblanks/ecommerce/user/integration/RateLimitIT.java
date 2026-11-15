package io.github.joeyblanks.ecommerce.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The rate limits on the real application: the service-token endpoint (per address for failures, per client for
 * successes) and the per-account login throttle. Limits are small here so the tests are quick. Every test uses its
 * own addresses, emails and clients because the counters live as long as the application. Run by Failsafe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static final String SECRET = "rate-limit-test-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        registry.add("app.jwt.private-key", TestKeys::sharedBase64Pem);
        registry.add("app.jwt.issuer", () -> "user-service");
        String[] ids = {"client-a", "client-b", "client-c", "client-d", "client-e"};
        for (int i = 0; i < ids.length; i++) {
            int n = i;
            registry.add("app.service-tokens.clients[" + n + "].client-id", () -> ids[n]);
            registry.add("app.service-tokens.clients[" + n + "].secret-hash", () -> bcrypt.encode(SECRET));
            registry.add("app.service-tokens.clients[" + n + "].scopes[0]", () -> "stock:reserve");
            registry.add("app.service-tokens.clients[" + n + "].audience", () -> "product-catalog-service");
        }
        registry.add("app.rate-limits.login.max-failures", () -> "3");
        registry.add("app.rate-limits.token.max-failures-per-address", () -> "4");
        registry.add("app.rate-limits.token.max-per-client", () -> "3");
    }

    @Autowired MockMvc mvc;

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private MockHttpServletRequestBuilder token(String client, String secret, String address, String... forwardedFor) {
        MockHttpServletRequestBuilder b = post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials").param("scope", "stock:reserve")
                .with(httpBasic(client, secret)).with(r -> {
                    r.setRemoteAddr(address);
                    return r;
                });
        for (String f : forwardedFor) {
            b.header("X-Forwarded-For", f);
        }
        return b;
    }

    private int tokenStatus(String client, String secret, String address, String... forwardedFor) throws Exception {
        return mvc.perform(token(client, secret, address, forwardedFor)).andReturn().getResponse().getStatus();
    }

    // ---------- service-token endpoint ----------

    @Test
    void guessingASecretFromOneAddressIsStoppedAndEvenTheRightSecretIsRefusedForNow() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(tokenStatus("client-a", "guess-" + i, "10.1.0.1")).isEqualTo(401);
        }

        MockHttpServletResponse blocked = mvc.perform(token("client-a", SECRET, "10.1.0.1")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.access_token").doesNotExist()).andExpect(jsonPath("$.error").value("too_many_requests"))
                .andReturn().getResponse();
        assertThat(Long.parseLong(blocked.getHeader("Retry-After"))).isBetween(1L, 60L);
    }

    @Test
    void anotherAddressCanStillGetATokenWhileTheAttackerIsBlocked() throws Exception {
        for (int i = 0; i < 4; i++) {
            tokenStatus("client-b", "guess-" + i, "10.2.0.66");
        }
        assertThat(tokenStatus("client-b", SECRET, "10.2.0.66")).isEqualTo(429);

        assertThat(tokenStatus("client-b", SECRET, "10.2.0.1")).as("the real service, at its own address").isEqualTo(200);
    }

    @Test
    void aForgedForwardedForHeaderDoesNotDodgeTheLimit() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(tokenStatus("client-c", "guess-" + i, "10.3.0.66", "203.0.113." + i)).isEqualTo(401);
        }

        assertThat(tokenStatus("client-c", "guess-5", "10.3.0.66", "198.51.100.200")).isEqualTo(429);
        assertThat(tokenStatus("client-c", SECRET, "10.3.0.66", "10.3.0.1")).as("claiming to be someone else").isEqualTo(429);
    }

    @Test
    void aClientThatKeepsAskingForTokensIsLimitedButOthersAreNot() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(tokenStatus("client-d", SECRET, "10.4.0." + i)).isEqualTo(200);
        }

        assertThat(tokenStatus("client-d", SECRET, "10.4.0.9")).isEqualTo(429);
        assertThat(tokenStatus("client-a", SECRET, "10.4.0.9")).as("another client, same address").isEqualTo(200);
    }

    @Test
    void anAttackersFailuresWithAServiceIdNeverUseUpThatServicesBudget() throws Exception {
        // client-e's budget is 3 successes. Fail 3 times (below the address limit of 4) with its id first.
        for (int i = 0; i < 3; i++) {
            tokenStatus("client-e", "guess-" + i, "10.5.0.66");
        }

        for (int i = 0; i < 3; i++) {
            assertThat(tokenStatus("client-e", SECRET, "10.5.0." + i)).isEqualTo(200);
        }
    }

    // ---------- login, per account ----------

    private int login(String email, String password) throws Exception {
        return mvc.perform(post("/api/users/login").contentType(JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getStatus();
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/users/register").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"fullName\":\"X\"}"))
                .andExpect(status().isCreated());
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void tooManyBadPasswordsBlockThatAccountEvenForTheCorrectPassword() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "wrong-password")).isEqualTo(401);
        }

        mvc.perform(post("/api/users/login").contentType(JSON).content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void anUnknownEmailGetsTheSameTreatmentSoNothingIsRevealed() throws Exception {
        String known = uniqueEmail();
        register(known);
        String unknown = uniqueEmail();
        for (int i = 0; i < 3; i++) {
            assertThat(login(known, "wrong-password")).isEqualTo(401);
            assertThat(login(unknown, "wrong-password")).isEqualTo(401);
        }

        assertThat(login(known, "wrong-password")).isEqualTo(429);
        assertThat(login(unknown, "wrong-password")).isEqualTo(429);
    }

    @Test
    void otherAccountsAndSuccessfulLoginsAreUnaffected() throws Exception {
        String victim = uniqueEmail();
        String other = uniqueEmail();
        register(victim);
        register(other);
        for (int i = 0; i < 3; i++) {
            login(victim, "wrong-password");
        }

        assertThat(login(victim, "password123")).isEqualTo(429);
        assertThat(login(other, "password123")).isEqualTo(200);
    }

    @Test
    void successResetsTheCountSoNormalTypingMistakesNeverAccumulate() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int round = 0; round < 4; round++) {
            assertThat(login(email, "wrong-password")).isEqualTo(401);
            assertThat(login(email, "wrong-password")).isEqualTo(401);
            assertThat(login(email, "password123")).isEqualTo(200);
        }
    }

    @Test
    void registerAndOtherEndpointsAreNotLimitedHere() throws Exception {
        // per-IP limits on register belong to the gateway (ADR 011); user-service must not add a second, surprising one
        for (int i = 0; i < 6; i++) {
            register(uniqueEmail());
        }
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
    }
}
