package io.github.joeyblanks.ecommerce.notification.user;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gets this service's own access token from user-service (OAuth2 client credentials, ADR 008) and caches it
 * until shortly before it expires, so the token endpoint is called every few minutes, not once per email.
 * The scope is requested explicitly: a token without it is accepted nowhere.
 */
@Component
public class ServiceTokenClient {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final RestClient http;
    private final AppProperties.UserService props;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceTokenClient(RestClient.Builder builder, AppProperties.UserService props, Clock clock) {
        this.http = HttpClients.create(builder, props.tokenUri(), props.connectTimeout(), props.readTimeout());
        this.props = props;
        this.clock = clock;
    }

    public synchronized String token() {
        if (token != null && clock.instant().isBefore(expiresAt.minus(REFRESH_MARGIN))) {
            return token;
        }
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new ContactUnavailableException(
                    "NOTIFICATION_SERVICE_CLIENT_SECRET is not configured, so no service token can be obtained", null);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", props.scope());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = http.post()
                    .headers(h -> h.setBasicAuth(props.clientId(), props.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String accessToken)) {
                throw new ContactUnavailableException("Token endpoint returned no access token", null);
            }
            long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
            token = accessToken;
            expiresAt = clock.instant().plusSeconds(expiresIn);
            return token;
        } catch (RestClientException e) {
            throw new ContactUnavailableException("Could not obtain a service token", e);
        }
    }

    /** Forget the cached token (e.g. after user-service rejected it). */
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }
}
