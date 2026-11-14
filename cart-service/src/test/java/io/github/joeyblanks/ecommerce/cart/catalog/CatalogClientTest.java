package io.github.joeyblanks.ecommerce.cart.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joeyblanks.ecommerce.cart.config.CatalogProperties;
import io.github.joeyblanks.ecommerce.cart.support.CatalogStub;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Real HTTP against a stub: proves the timeouts and the failure mapping, not just mocks. */
class CatalogClientTest {

    private final CatalogStub stub = new CatalogStub();
    private CatalogClient client;

    private static CatalogClient clientFor(String url, Duration timeout) {
        return new CatalogClient(RestClient.builder(), new CatalogProperties(url, timeout, timeout));
    }

    @BeforeEach
    void setUp() {
        client = clientFor(stub.url(), Duration.ofMillis(700));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void returnsProductAndIgnoresFieldsItDoesNotNeed() {
        CatalogStub.Product p = stub.put("CC-1", "Clean Code", "29.90", 12);

        CatalogProduct product = client.getProduct(p.id());

        assertThat(product.id()).isEqualTo(p.id());
        assertThat(product.sku()).isEqualTo("CC-1");
        assertThat(product.name()).isEqualTo("Clean Code");
        assertThat(product.price()).isEqualByComparingTo("29.90");
        assertThat(product.stockQuantity()).isEqualTo(12);
    }

    @Test
    void unknownProductIsNotFound() {
        assertThatThrownBy(() -> client.getProduct(UUID.randomUUID())).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void serverErrorIsUnavailable() {
        CatalogStub.Product p = stub.put("A", "A", "1.00", 1);
        stub.mode(CatalogStub.Mode.ERROR_500);

        assertThatThrownBy(() -> client.getProduct(p.id())).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void garbledAndEmptyBodiesAreUnavailable() {
        CatalogStub.Product p = stub.put("A", "A", "1.00", 1);

        stub.mode(CatalogStub.Mode.GARBAGE);
        assertThatThrownBy(() -> client.getProduct(p.id())).isInstanceOf(CatalogUnavailableException.class);
        stub.mode(CatalogStub.Mode.EMPTY_BODY);
        assertThatThrownBy(() -> client.getProduct(p.id())).isInstanceOf(CatalogUnavailableException.class);
    }

    @Test
    void slowCatalogFailsQuicklyInsteadOfHanging() {
        CatalogStub.Product p = stub.put("A", "A", "1.00", 1);
        stub.delay(Duration.ofSeconds(5));

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.getProduct(p.id())).isInstanceOf(CatalogUnavailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("must give up near the 700 ms read timeout, not wait 5 s").isLessThan(3_000);
    }

    @Test
    void refusedConnectionIsUnavailable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        } // closed again: nothing is listening on this port

        CatalogClient dead = clientFor("http://localhost:" + closedPort, Duration.ofMillis(700));

        assertThatThrownBy(() -> dead.getProduct(UUID.randomUUID())).isInstanceOf(CatalogUnavailableException.class);
    }
}
