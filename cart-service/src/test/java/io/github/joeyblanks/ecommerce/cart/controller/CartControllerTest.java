package io.github.joeyblanks.ecommerce.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.cart.catalog.CatalogUnavailableException;
import io.github.joeyblanks.ecommerce.cart.catalog.ProductNotFoundException;
import io.github.joeyblanks.ecommerce.cart.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.cart.dto.CartResponse;
import io.github.joeyblanks.ecommerce.cart.repository.CartStorageUnavailableException;
import io.github.joeyblanks.ecommerce.cart.service.CartBusyException;
import io.github.joeyblanks.ecommerce.cart.service.CartLimitException;
import io.github.joeyblanks.ecommerce.cart.service.CartService;
import io.github.joeyblanks.ecommerce.cart.service.InsufficientStockException;
import io.github.joeyblanks.ecommerce.cart.service.InvalidRequestException;
import io.github.joeyblanks.ecommerce.cart.service.ItemNotInCartException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Web-layer slice: real security chain, validation and error mapping; mocked CartService. */
@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
// The JWKS URL is built lazily and never fetched here (jwt() bypasses token decoding).
@TestPropertySource(properties = {
        "app.security.jwk-set-uri=http://localhost:1/jwks.json", "app.security.issuer=user-service",
        "app.cart.ttl=P7D", "app.cart.max-lines=50", "app.cart.max-quantity=99", "app.cart.cas-max-attempts=3",
        "app.catalog.base-url=http://localhost:1", "app.catalog.connect-timeout=1s", "app.catalog.read-timeout=1s"})
class CartControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CartService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final CartResponse EMPTY = CartResponse.of(List.of());
    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String ADD = "{\"productId\":\"" + PRODUCT + "\",\"quantity\":2}";

    private static RequestPostProcessor user() {
        return jwt().jwt(j -> j.subject(USER.toString()));
    }

    // ---- authentication: every endpoint needs a valid token ----

    @Test
    void everyCartEndpointRequiresAToken() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cart/items").contentType(JSON).content(ADD)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/cart/items/" + PRODUCT).contentType(JSON).content("{\"quantity\":1}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart/items/" + PRODUCT)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void healthIsPublicButOtherPathsAreNot() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/something-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCartIsChosenByTheTokenSubjectAndNothingElse() throws Exception {
        when(service.view(USER)).thenReturn(EMPTY);

        // A different user id smuggled in via query string or header must have no effect.
        mvc.perform(get("/api/cart").param("userId", UUID.randomUUID().toString())
                        .header("X-User-Id", UUID.randomUUID().toString()).with(user()))
                .andExpect(status().isOk());

        verify(service).view(USER);
    }

    @Test
    void anyRoleMayUseItsOwnCart() throws Exception {
        when(service.view(USER)).thenReturn(EMPTY);

        for (String role : new String[] {"ROLE_CUSTOMER", "ROLE_ADMIN"}) {
            mvc.perform(get("/api/cart").with(jwt().jwt(j -> j.subject(USER.toString()))
                    .authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isOk());
        }
        // even a token without any role
        mvc.perform(get("/api/cart").with(user())).andExpect(status().isOk());
    }

    @Test
    void aSubjectThatIsNotAUserIdIsRejectedWith401() throws Exception {
        mvc.perform(get("/api/cart").with(jwt().jwt(j -> j.subject("../other-cart"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ---- happy paths ----

    @Test
    void endpointsPassTheUserAndArgumentsToTheService() throws Exception {
        when(service.view(USER)).thenReturn(EMPTY);
        when(service.addItem(USER, PRODUCT, 2)).thenReturn(EMPTY);
        when(service.setQuantity(USER, PRODUCT, 5)).thenReturn(EMPTY);
        when(service.removeItem(USER, PRODUCT)).thenReturn(EMPTY);

        mvc.perform(get("/api/cart").with(user())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD)).andExpect(status().isOk());
        mvc.perform(put("/api/cart/items/" + PRODUCT).with(user()).contentType(JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/cart/items/" + PRODUCT).with(user())).andExpect(status().isOk());
        mvc.perform(delete("/api/cart").with(user())).andExpect(status().isNoContent());

        verify(service).addItem(USER, PRODUCT, 2);
        verify(service).setQuantity(USER, PRODUCT, 5);
        verify(service).removeItem(USER, PRODUCT);
        verify(service).clear(USER);
    }

    // ---- validation ----

    @Test
    void invalidRequestsAre400AndNeverReachTheService() throws Exception {
        String[] badAdds = {
                "{\"productId\":\"" + PRODUCT + "\",\"quantity\":0}",
                "{\"productId\":\"" + PRODUCT + "\",\"quantity\":-3}",
                "{\"quantity\":1}",
                "{\"productId\":\"nope\",\"quantity\":1}",
                "{\"productId\":\"" + PRODUCT + "\",\"quantity\":\"many\"}",
                "not json"};
        for (String body : badAdds) {
            mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/cart/items/" + PRODUCT).with(user()).contentType(JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/cart/items/not-a-uuid").with(user()).contentType(JSON).content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).addItem(any(), any(), anyInt());
        verify(service, never()).setQuantity(any(), any(), anyInt());
    }

    // ---- error mapping ----

    @Test
    void unknownProductAndMissingLineAre404() throws Exception {
        when(service.addItem(any(), any(), anyInt())).thenThrow(new ProductNotFoundException(PRODUCT));
        when(service.setQuantity(any(), any(), anyInt())).thenThrow(new ItemNotInCartException(PRODUCT));
        when(service.removeItem(any(), any())).thenThrow(new ItemNotInCartException(PRODUCT));

        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD)).andExpect(status().isNotFound());
        mvc.perform(put("/api/cart/items/" + PRODUCT).with(user()).contentType(JSON).content("{\"quantity\":1}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/cart/items/" + PRODUCT).with(user())).andExpect(status().isNotFound());
    }

    @Test
    void notEnoughStockIs409AndTellsHowMuchIsAvailable() throws Exception {
        when(service.addItem(any(), any(), anyInt())).thenThrow(new InsufficientStockException(3, 5));

        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.availableStock").value(3))
                .andExpect(jsonPath("$.requestedTotal").value(5))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("3 available")));
    }

    @Test
    void limitsAndContentionAre409() throws Exception {
        when(service.addItem(eq(USER), any(), eq(2)))
                .thenThrow(new CartLimitException("A cart can hold at most 50 different products"))
                .thenThrow(new CartBusyException());

        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD)).andExpect(status().isConflict());
        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD)).andExpect(status().isConflict());
    }

    @Test
    void serviceLevelInvalidRequestIs400() throws Exception {
        when(service.addItem(any(), any(), anyInt())).thenThrow(new InvalidRequestException("quantity must be between 1 and 99"));

        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD)).andExpect(status().isBadRequest());
    }

    @Test
    void catalogOrStorageOutageIs503WithRetryAfterAndAClearMessage() throws Exception {
        when(service.addItem(any(), any(), anyInt())).thenThrow(new CatalogUnavailableException("down", null));
        when(service.view(any())).thenThrow(new CartStorageUnavailableException(new RuntimeException("redis down")));

        mvc.perform(post("/api/cart/items").with(user()).contentType(JSON).content(ADD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("catalog is temporarily unavailable")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cart is unchanged")));
        mvc.perform(get("/api/cart").with(user()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Cart storage")));
    }
}
