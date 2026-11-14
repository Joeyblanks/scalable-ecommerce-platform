package io.github.joeyblanks.ecommerce.cart.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.cart.support.CatalogStub;
import io.github.joeyblanks.ecommerce.cart.support.TestJwks;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Own container, because this test stops Redis. Proves a Redis outage becomes a clear, quick 503. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CartRedisOutageIT {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static final TestJwks JWKS = new TestJwks();
    static final CatalogStub CATALOG = new CatalogStub();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwk-set-uri", JWKS::url);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
        registry.add("app.catalog.base-url", CATALOG::url);
    }

    @AfterAll
    static void stopStubs() {
        JWKS.close();
        CATALOG.close();
    }

    @Autowired MockMvc mvc;

    @Test
    void whenRedisIsDownEveryCartEndpointAnswers503QuicklyWithRetryAfter() throws Exception {
        UUID user = UUID.randomUUID();
        String auth = "Bearer " + JWKS.token(user.toString(), "CUSTOMER");
        var book = CATALOG.put("CC-1", "Clean Code", "29.90", 50);
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + book.id() + "\",\"quantity\":1}")).andExpect(status().isOk());

        REDIS.stop();

        long start = System.nanoTime();
        mvc.perform(get("/api/cart").header("Authorization", auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Cart storage")));
        mvc.perform(post("/api/cart/items").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + book.id() + "\",\"quantity\":1}")).andExpect(status().isServiceUnavailable());
        mvc.perform(delete("/api/cart").header("Authorization", auth)).andExpect(status().isServiceUnavailable());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("three failing calls must not hang").isLessThan(8_000);
    }
}
