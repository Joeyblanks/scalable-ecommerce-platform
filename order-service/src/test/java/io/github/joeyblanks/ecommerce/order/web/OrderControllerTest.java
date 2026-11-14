package io.github.joeyblanks.ecommerce.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.order.client.DependencyUnavailableException;
import io.github.joeyblanks.ecommerce.order.client.OutOfStockException;
import io.github.joeyblanks.ecommerce.order.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.order.order.EmptyCartException;
import io.github.joeyblanks.ecommerce.order.order.OrderNotCompletedException;
import io.github.joeyblanks.ecommerce.order.order.OrderPlacementService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web slice: real security chain and error mapping, mocked placement service. */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(io.github.joeyblanks.ecommerce.order.config.AppProperties.Security.class)
@TestPropertySource(properties = {"app.security.jwk-set-uri=http://localhost:1/jwks.json", "app.security.issuer=user-service"})
class OrderControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderPlacementService orders;

    private static final UUID USER = UUID.randomUUID();

    private static RequestPostProcessor user() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("roles", List.of("CUSTOMER")));
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("order-service").claim("client_id", "order-service"));
    }

    @Test
    void serviceTokensAreRefusedEverywhere() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").with(service())).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders").with(service())).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + id).with(service())).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + id + "/cancel").with(service())).andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test
    void theIdempotencyKeyIsRequiredAndValidated() throws Exception {
        mvc.perform(post("/api/orders").with(user())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "x".repeat(101)).with(user())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "bad key").with(user())).andExpect(status().isBadRequest());
        verifyNoInteractions(orders);
    }

    @Test
    void failuresMapToClearStatuses() throws Exception {
        when(orders.place(any(), anyString(), anyString()))
                .thenThrow(new EmptyCartException())
                .thenThrow(new OutOfStockException(List.of(Map.of("productId", "p", "available", 0))))
                .thenThrow(new DependencyUnavailableException("product-catalog-service", "down", null))
                .thenThrow(new OrderNotCompletedException(null));

        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").with(user())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").with(user())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.shortages[0].productId").value("p"));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").with(user())).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("product-catalog-service")));
        mvc.perform(post("/api/orders").header("Idempotency-Key", "k").with(user())).andExpect(status().isServiceUnavailable());
    }
}
