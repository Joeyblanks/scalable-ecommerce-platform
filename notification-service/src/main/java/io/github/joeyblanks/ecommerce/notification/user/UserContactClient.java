package io.github.joeyblanks.ecommerce.notification.user;

import io.github.joeyblanks.ecommerce.notification.config.AppProperties;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Asks user-service where to send mail to a user (GET /api/internal/users/{id}/contact, ADR 008, 010).
 * 404 means the user is gone (permanent); anything else that is not a clear answer is "try again later".
 */
@Component
public class UserContactClient {

    private final RestClient http;
    private final ServiceTokenClient tokens;

    public UserContactClient(RestClient.Builder builder, AppProperties.UserService props, ServiceTokenClient tokens) {
        this.http = HttpClients.create(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
        this.tokens = tokens;
    }

    public Contact contactOf(UUID userId) {
        try {
            Contact contact = http.get().uri("/api/internal/users/{id}/contact", userId)
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new UserNotFoundException(userId);
                    })
                    .body(Contact.class);
            if (contact == null || contact.email() == null || contact.email().isBlank()) {
                throw new ContactUnavailableException("user-service returned no email address", null);
            }
            return contact;
        } catch (UserNotFoundException e) {
            throw e;
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 401 || status.value() == 403) {
                tokens.invalidate(); // maybe a stale token: get a fresh one on the next attempt
            }
            throw new ContactUnavailableException("user-service answered " + status.value(), e);
        } catch (RestClientException e) {
            throw new ContactUnavailableException("Could not reach user-service", e);
        }
    }
}
