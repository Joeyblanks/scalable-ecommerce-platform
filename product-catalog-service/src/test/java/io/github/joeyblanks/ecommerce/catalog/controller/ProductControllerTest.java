package io.github.joeyblanks.ecommerce.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.catalog.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.catalog.dto.PageResponse;
import io.github.joeyblanks.ecommerce.catalog.dto.ProductResponse;
import io.github.joeyblanks.ecommerce.catalog.service.ConflictException;
import io.github.joeyblanks.ecommerce.catalog.service.NotFoundException;
import io.github.joeyblanks.ecommerce.catalog.service.ProductService;
import java.math.BigDecimal;
import java.time.Instant;
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

/** Web-layer slice: real security chain and validation/error mapping, mocked service. */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
// The JWKS URL is built lazily and never fetched in these tests (jwt() bypasses token decoding).
@TestPropertySource(properties = {"app.security.jwk-set-uri=http://localhost:1/jwks.json", "app.security.issuer=user-service"})
class ProductControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ProductService service;

    private static final UUID CATEGORY = UUID.randomUUID();
    private static final String BODY = "{\"sku\":\"CC-1\",\"name\":\"Clean Code\",\"price\":29.90,\"stockQuantity\":5,\"categoryId\":\"" + CATEGORY + "\"}";

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor customer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static ProductResponse product(UUID id) {
        return new ProductResponse(id, "CC-1", "Clean Code", null, new BigDecimal("29.90"), 5, CATEGORY, "Books",
                Instant.EPOCH, Instant.EPOCH);
    }

    // ---- public reads ----

    @Test
    void listIsPublicAndPassesFiltersAndPaging() throws Exception {
        when(service.list(any(), any(), any())).thenReturn(new PageResponse<>(List.of(product(UUID.randomUUID())), 0, 20, 1, 1));

        mvc.perform(get("/api/products").param("categoryId", CATEGORY.toString()).param("q", "clean"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].categoryName").value("Books"));
        verify(service).list(eq(CATEGORY), eq("clean"), any());
    }

    @Test
    void getByIdIsPublicAnd404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(service.get(id)).thenReturn(product(id));
        when(service.get(missing)).thenThrow(new NotFoundException("Product not found: " + missing));

        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.sku").value("CC-1"));
        mvc.perform(get("/api/products/" + missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/products/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidPagingAndSortAreBadRequests() throws Exception {
        mvc.perform(get("/api/products").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("sort", "passwordHash")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("categoryId", "nope")).andExpect(status().isBadRequest());
        verify(service, never()).list(any(), any(), any());
    }

    // ---- write access matrix: 401 anonymous, 403 customer, success admin ----

    @Test
    void writesWithoutTokenAre401() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/products/" + id).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/products/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/products").header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writesAsCustomerAre403AndNeverReachTheService() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/products").with(customer()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put("/api/products/" + id).with(customer()).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/" + id).with(customer())).andExpect(status().isForbidden());
        verify(service, never()).create(any());
        verify(service, never()).update(any(), any());
        verify(service, never()).delete(any());
    }

    @Test
    void adminCanCreateUpdateDelete() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(product(id));
        when(service.update(eq(id), any())).thenReturn(product(id));

        mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/products/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(put("/api/products/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/products/" + id).with(admin())).andExpect(status().isNoContent());
        verify(service).delete(id);
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        String[] bad = {
                "{\"sku\":\"\",\"name\":\"x\",\"price\":1,\"stockQuantity\":1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A B\",\"name\":\"x\",\"price\":1,\"stockQuantity\":1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A\",\"name\":\"\",\"price\":1,\"stockQuantity\":1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A\",\"name\":\"x\",\"price\":-0.01,\"stockQuantity\":1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A\",\"name\":\"x\",\"price\":1.999,\"stockQuantity\":1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A\",\"name\":\"x\",\"price\":1,\"stockQuantity\":-1,\"categoryId\":\"" + CATEGORY + "\"}",
                "{\"sku\":\"A\",\"name\":\"x\",\"price\":1,\"stockQuantity\":1}",
                "{\"sku\":\"A\",\"name\":\"x\",\"price\":1,\"stockQuantity\":1,\"categoryId\":\"nope\"}",
                "not json"};
        for (String body : bad) {
            mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).create(any());
    }

    @Test
    void conflictAndNotFoundAreMapped() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenThrow(new ConflictException("dup"));
        when(service.update(eq(id), any())).thenThrow(new NotFoundException("gone"));

        mvc.perform(post("/api/products").with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/products/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownPathsAreNotPublic() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }
}
