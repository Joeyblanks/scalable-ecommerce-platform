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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.catalog.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.catalog.dto.CategoryResponse;
import io.github.joeyblanks.ecommerce.catalog.dto.PageResponse;
import io.github.joeyblanks.ecommerce.catalog.service.CategoryService;
import io.github.joeyblanks.ecommerce.catalog.service.ConflictException;
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

@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {"app.security.jwk-set-uri=http://localhost:1/jwks.json", "app.security.issuer=user-service"})
class CategoryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean CategoryService service;

    private static final String BODY = "{\"name\":\"Books\",\"description\":\"Paper\"}";

    private static CategoryResponse category(UUID id) {
        return new CategoryResponse(id, "Books", "Paper", Instant.EPOCH);
    }

    @Test
    void listAndGetArePublic() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list(any())).thenReturn(new PageResponse<>(List.of(category(id)), 0, 20, 1, 1));
        when(service.get(id)).thenReturn(category(id));

        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Books"));
        mvc.perform(get("/api/categories/" + id)).andExpect(status().isOk());
    }

    @Test
    void invalidPagingIsBadRequest() throws Exception {
        mvc.perform(get("/api/categories").param("size", "1000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/categories").param("sort", "description")).andExpect(status().isBadRequest());
    }

    @Test
    void writeMatrix() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(category(id));
        when(service.update(eq(id), any())).thenReturn(category(id));
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        var customer = jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));

        mvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/categories").with(customer).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(put("/api/categories/" + id).with(customer).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/categories/" + id).with(customer)).andExpect(status().isForbidden());
        verify(service, never()).create(any());

        mvc.perform(post("/api/categories").with(admin).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/categories/" + id).with(admin).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/categories/" + id).with(admin)).andExpect(status().isNoContent());
    }

    @Test
    void invalidBodyIs400AndConflictIs409() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(post("/api/categories").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());

        when(service.create(any())).thenThrow(new ConflictException("dup"));
        mvc.perform(post("/api/categories").with(admin).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ConflictException("has products")).when(service).delete(id);
        mvc.perform(delete("/api/categories/" + id).with(admin)).andExpect(status().isConflict());
    }
}
