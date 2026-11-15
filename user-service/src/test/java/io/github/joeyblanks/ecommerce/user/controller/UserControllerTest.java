package io.github.joeyblanks.ecommerce.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joeyblanks.ecommerce.user.config.JwtConfig;
import io.github.joeyblanks.ecommerce.user.config.SecurityConfig;
import io.github.joeyblanks.ecommerce.user.dto.ProfileResponse;
import io.github.joeyblanks.ecommerce.user.dto.TokenResponse;
import io.github.joeyblanks.ecommerce.user.model.Role;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.github.joeyblanks.ecommerce.user.service.EmailAlreadyExistsException;
import io.github.joeyblanks.ecommerce.user.service.InvalidCredentialsException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import io.github.joeyblanks.ecommerce.user.service.UserService;

/** Web-layer slice: real security filter chain + validation + error mapping, mocked UserService. */
@WebMvcTest({UserController.class, JwksController.class})
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = {"app.jwt.issuer=user-service", "app.jwt.expiration=PT1H"})
class UserControllerTest {

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.private-key", TestKeys::sharedBase64Pem);
    }

    @Autowired MockMvc mvc;
    @Autowired com.nimbusds.jose.jwk.RSAKey signingKey;
    @MockitoBean UserService userService;

    private static final String VALID_REGISTER =
            "{\"email\":\"ann@example.com\",\"password\":\"password123\",\"fullName\":\"Ann\"}";

    @Test
    void registerReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.register(any())).thenReturn(new ProfileResponse(id, "ann@example.com", "Ann", Role.CUSTOMER, Instant.now()));

        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("ann@example.com"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registerWithInvalidBodyReturns400() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDuplicateReturns409() throws Exception {
        when(userService.register(any())).thenThrow(new EmailAlreadyExistsException("ann@example.com"));

        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON).content(VALID_REGISTER))
                .andExpect(status().isConflict());
    }

    @Test
    void loginReturnsToken() throws Exception {
        when(userService.login(any())).thenReturn(new TokenResponse("the-jwt", "Bearer", 3600));

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("the-jwt"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void loginWithBadCredentialsReturns401() throws Exception {
        when(userService.login(any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meWithGarbageTokenReturns401() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsProfile() throws Exception {
        UUID id = UUID.randomUUID();
        when(userService.getProfile(id)).thenReturn(new ProfileResponse(id, "ann@example.com", "Ann", Role.CUSTOMER, Instant.now()));

        mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.subject(id.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ann@example.com"));
    }

    @Test
    void jwksIsPublicAndExposesOnlyPublicKeyMaterial() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid").value(signingKey.getKeyID()))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty())
                // private RSA parameters must never be published
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist())
                .andExpect(jsonPath("$.keys[0].dp").doesNotExist())
                .andExpect(jsonPath("$.keys[0].dq").doesNotExist())
                .andExpect(jsonPath("$.keys[0].qi").doesNotExist());
    }

    @Test
    void tooManyFailedLoginsIs429WithRetryAfterAndNoDetailsAboutTheAccount() throws Exception {
        when(userService.login(any())).thenThrow(new io.github.joeyblanks.ecommerce.user.ratelimit.TooManyAttemptsException(
                "Too many failed login attempts. Try again later.", java.time.Duration.ofSeconds(90)));

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts. Try again later."));
    }
}
