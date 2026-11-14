package io.github.joeyblanks.ecommerce.cart.catalog;

import io.github.joeyblanks.ecommerce.cart.config.CatalogProperties;
import java.net.http.HttpClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks product-catalog-service about a product (its read endpoints are public, so no token is
 * forwarded). Short connect/read timeouts and no retries: a slow catalog must turn into a quick,
 * clear failure for the shopper instead of a hanging request.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder, CatalogProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        this.restClient = builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    /**
     * @throws ProductNotFoundException    the catalog answered 404
     * @throws CatalogUnavailableException any other failure: refused, timed out, 5xx, bad payload
     */
    public CatalogProduct getProduct(UUID productId) {
        try {
            CatalogProduct product = restClient.get().uri("/api/products/{id}", productId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new ProductNotFoundException(productId);
                    })
                    .body(CatalogProduct.class);
            if (product == null || product.id() == null || product.price() == null
                    || product.stockQuantity() < 0 || !productId.equals(product.id())) {
                throw new CatalogUnavailableException("Catalog returned an unusable product response", null);
            }
            return product;
        } catch (ProductNotFoundException | CatalogUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            // Covers refused/reset connections, timeouts, non-404 error statuses and unreadable bodies.
            log.warn("Catalog call for product {} failed: {}", productId, e.toString());
            throw new CatalogUnavailableException("Product catalog is unavailable", e);
        }
    }
}
