package io.github.joeyblanks.ecommerce.user.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joeyblanks.ecommerce.user.config.RateLimitProperties;
import io.github.joeyblanks.ecommerce.user.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TokenEndpointRateLimitFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
    private final TokenEndpointLimits limits = new TokenEndpointLimits(new RateLimitProperties(
            new RateLimitProperties.Login(10, Duration.ofMinutes(15)),
            new RateLimitProperties.Token(3, Duration.ofMinutes(1), 2, Duration.ofMinutes(1)), 100), clock);
    private final TokenEndpointRateLimitFilter filter = new TokenEndpointRateLimitFilter(limits);

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** One request through the filter; the "token endpoint" answers {@code status}. */
    private MockHttpServletResponse call(String address, String authorization, int status, String... forwardedFor) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/oauth2/token");
        req.setRemoteAddr(address);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        for (String f : forwardedFor) {
            req.addHeader("X-Forwarded-For", f);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(status);
            }
        });
        return res;
    }

    @Test
    void failedRequestsFromOneAddressAreBlockedWithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("10.0.0.1", basic("order-service", "bad"), 401).getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse blocked = call("10.0.0.1", basic("order-service", "right"), 200);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("too_many_requests").doesNotContain("access_token");
    }

    @Test
    void anotherAddressIsNotAffected() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", basic("order-service", "bad"), 401);
        }

        assertThat(call("10.0.0.2", basic("order-service", "right"), 200).getStatus()).isEqualTo(200);
    }

    @Test
    void aSpoofedForwardedForHeaderChangesNothing() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", basic("order-service", "bad"), 401, "198.51.100." + i);
        }

        assertThat(call("10.0.0.1", basic("order-service", "right"), 200, "203.0.113.77").getStatus())
                .as("the real connection address is the key, whatever the header says").isEqualTo(429);
    }

    @Test
    void badRequestsCountAsFailuresToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", null, 400);
        }

        assertThat(call("10.0.0.1", null, 400).getStatus()).isEqualTo(429);
    }

    @Test
    void successfulRequestsAreLimitedPerClientNotPerAddress() throws Exception {
        assertThat(call("10.0.0.1", basic("order-service", "s"), 200).getStatus()).isEqualTo(200);
        assertThat(call("10.0.0.2", basic("order-service", "s"), 200).getStatus()).isEqualTo(200);

        assertThat(call("10.0.0.3", basic("order-service", "s"), 200).getStatus()).isEqualTo(429);
        assertThat(call("10.0.0.3", basic("notification-service", "s"), 200).getStatus()).as("another client").isEqualTo(200);
    }

    @Test
    void failuresWithAnotherClientsIdNeverUseUpOrBlockThatClient() throws Exception {
        // an attacker (10.9.9.9) fails twice with order-service's id: below the failure limit, and not a success
        call("10.9.9.9", basic("order-service", "guess1"), 401);
        call("10.9.9.9", basic("order-service", "guess2"), 401);

        assertThat(call("10.0.0.1", basic("order-service", "right"), 200).getStatus()).isEqualTo(200);
        assertThat(call("10.0.0.1", basic("order-service", "right"), 200).getStatus()).as("full budget of 2 successes").isEqualTo(200);
    }

    @Test
    void theBlockEndsAfterTheWindow() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("10.0.0.1", null, 401);
        }
        assertThat(call("10.0.0.1", null, 200).getStatus()).isEqualTo(429);

        clock.advance(Duration.ofSeconds(61));

        assertThat(call("10.0.0.1", null, 200).getStatus()).isEqualTo(200);
    }

    @Test
    void onlyPostRequestsAreFiltered() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/oauth2/token");
        get.setRemoteAddr("10.0.0.1");
        assertThat(filter.shouldNotFilter(get)).isTrue();
    }

    @Test
    void readsTheClientIdFromBasicCredentialsOnly() {
        assertThat(TokenEndpointRateLimitFilter.clientIdOf(basic("order-service", "s"))).isEqualTo("order-service");
        assertThat(TokenEndpointRateLimitFilter.clientIdOf("basic " + Base64.getEncoder().encodeToString("a%40b:s".getBytes()))).isEqualTo("a@b");
        assertThat(TokenEndpointRateLimitFilter.clientIdOf(null)).isNull();
        assertThat(TokenEndpointRateLimitFilter.clientIdOf("Bearer abc")).isNull();
        assertThat(TokenEndpointRateLimitFilter.clientIdOf("Basic !!!not-base64")).isNull();
        assertThat(TokenEndpointRateLimitFilter.clientIdOf("Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes()))).isNull();
        assertThat(TokenEndpointRateLimitFilter.clientIdOf(basic("x".repeat(300), "s"))).isNull();
    }
}
