package io.github.joeyblanks.ecommerce.order.client;

import io.github.joeyblanks.ecommerce.order.config.AppProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog's internal stock reservation API, called with this service's own service token.
 * The reservation id is the order id, so every call can be safely repeated.
 */
@Component
public class CatalogClient {

    public record Line(UUID productId, int quantity) {
    }

    /** What the catalog reserved, with the authoritative price read in the same transaction. */
    public record Reservation(List<Item> items) {
    }

    public record Item(UUID productId, String sku, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
    }

    private final RestClient http;
    private final ServiceTokenClient tokens;
    private final JsonMapper json;

    public CatalogClient(RestClient.Builder builder, AppProperties.Catalog props, ServiceTokenClient tokens, JsonMapper json) {
        this.http = HttpClients.create(builder, props.baseUrl(), props.connectTimeout(), props.readTimeout());
        this.tokens = tokens;
        this.json = json;
    }

    /**
     * @throws OutOfStockException            the catalog said no (nothing reserved; definitive)
     * @throws DependencyUnavailableException anything else, including timeouts: the outcome is UNKNOWN,
     *                                        so the caller must release this reservation id
     */
    public Reservation reserve(UUID reservationId, List<Line> lines) {
        Map<String, Object> body = Map.of("reservationId", reservationId, "items", lines);
        try {
            Reservation reservation = http.post().uri("/internal/stock/reservations")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        if (response.getStatusCode().value() == 409) {
                            JsonNode problem = json.readTree(response.getBody());
                            if (problem.has("shortages")) {
                                throw new OutOfStockException(json.convertValue(problem.get("shortages"),
                                        json.getTypeFactory().constructCollectionType(List.class, Map.class)));
                            }
                        }
                        if (response.getStatusCode().value() == 401) {
                            tokens.invalidate();
                        }
                        throw new HttpClientErrorException(response.getStatusCode(), "catalog refused the reservation");
                    })
                    .body(Reservation.class);
            if (reservation == null || reservation.items() == null || reservation.items().isEmpty()) {
                throw new DependencyUnavailableException("product-catalog-service", "Catalog returned no reservation", null);
            }
            return reservation;
        } catch (OutOfStockException | DependencyUnavailableException e) {
            throw e;
        } catch (RestClientException | IllegalArgumentException e) {
            throw new DependencyUnavailableException("product-catalog-service", "Product catalog is unavailable", e);
        }
    }

    /** Idempotent on the catalog side; also safe for ids that were never reserved (tombstone). */
    public void release(UUID reservationId) {
        try {
            http.post().uri("/internal/stock/reservations/{id}/release", reservationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token())
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (request, response) -> {
                        tokens.invalidate();
                        throw new HttpClientErrorException(response.getStatusCode(), "catalog rejected the service token");
                    })
                    .toBodilessEntity();
        } catch (DependencyUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new DependencyUnavailableException("product-catalog-service", "Product catalog is unavailable", e);
        }
    }
}
