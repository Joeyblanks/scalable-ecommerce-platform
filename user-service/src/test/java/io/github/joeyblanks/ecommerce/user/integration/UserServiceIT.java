package io.github.joeyblanks.ecommerce.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.joeyblanks.ecommerce.user.config.AdminBootstrapRunner;
import io.github.joeyblanks.ecommerce.user.config.BootstrapAdminProperties;
import io.github.joeyblanks.ecommerce.user.model.Role;
import io.github.joeyblanks.ecommerce.user.model.User;
import io.github.joeyblanks.ecommerce.user.service.UserService;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import io.github.joeyblanks.ecommerce.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Full-stack tests: real Spring context + real security chain + Flyway + PostgreSQL in a container.
 * Run by Maven Failsafe ({@code *IT}) during {@code verify}; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"app.jwt.issuer=user-service", "app.jwt.expiration=PT1H"})
class UserServiceIT {

    // Same major version as docker-compose.yml. @ServiceConnection wires the datasource automatically.
    // One container shared by all tests in this class; state is reset per test below.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.private-key", TestKeys::sharedBase64Pem);
    }

    @Autowired MockMvc mvc;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    @BeforeEach
    void cleanDatabase() {
        users.deleteAll();
    }

    private static final String ANN = "{\"email\":\"Ann@Example.com\",\"password\":\"password123\",\"fullName\":\"Ann\"}";

    private void register(String json) throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private String loginToken(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    // ---- schema ----

    @Test
    void flywayMigrationRanAgainstRealDatabase() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    // ---- register ----

    @Test
    void registerPersistsNormalisedEmailAndBcryptHash() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON).content(ANN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ann@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User saved = users.findByEmail("ann@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("password123");
        assertThat(saved.getFullName()).isEqualTo("Ann");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void registerDuplicateEmailReturns409EvenWithDifferentCase() throws Exception {
        register(ANN);

        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ANN@example.com\",\"password\":\"otherpass123\",\"fullName\":\"Other\"}"))
                .andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void registerInvalidBodyReturns400AndPersistsNothing() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nope\",\"password\":\"short\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.count()).isZero();
    }

    // ---- login ----

    @Test
    void loginReturnsUsableBearerToken() throws Exception {
        register(ANN);

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() throws Exception {
        register(ANN);
        assertThat(loginToken("ANN@EXAMPLE.COM", "password123")).isNotBlank();
    }

    @Test
    void loginWithWrongPasswordAndUnknownEmailBothReturn401() throws Exception {
        register(ANN);

        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- /me ----

    @Test
    void meReturnsProfileForTokenFromLogin() throws Exception {
        register(ANN);
        String token = loginToken("ann@example.com", "password123");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ann@example.com"))
                .andExpect(jsonPath("$.fullName").value("Ann"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void meWithoutOrWithGarbageTokenReturns401() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredTokenReturns401() throws Exception {
        register(ANN);
        UUID id = users.findByEmail("ann@example.com").orElseThrow().getId();
        String expired = signedToken(id, Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(1)));

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meForUserDeletedAfterLoginReturns404() throws Exception {
        register(ANN);
        String token = loginToken("ann@example.com", "password123");
        users.deleteAll();

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- RS256, JWKS and roles ----

    @Test
    void loginTokenVerifiesAgainstPublishedJwksOnly() throws Exception {
        register(ANN);
        String token = loginToken("ann@example.com", "password123");

        // Act as another service: fetch the public JWKS over HTTP and verify with it alone.
        String jwksJson = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jwksJson).doesNotContain("\"d\"");
        JWKSet jwks = JWKSet.parse(jwksJson);
        var publicKey = ((com.nimbusds.jose.jwk.RSAKey) jwks.getKeys().get(0)).toRSAPublicKey();
        Jwt jwt = NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token);

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", jwks.getKeys().get(0).getKeyID());
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("CUSTOMER");
    }

    @Test
    void registrationCannotCreateAnAdminEvenIfRoleIsSupplied() throws Exception {
        mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"evil@example.com\",\"password\":\"password123\",\"fullName\":\"Evil\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        assertThat(users.findByEmail("evil@example.com").orElseThrow().getRole()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void meReturnsRole() throws Exception {
        register(ANN);
        String token = loginToken("ann@example.com", "password123");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void bootstrapCreatesAdminOnceAndAdminTokenCarriesAdminRole() throws Exception {
        AdminBootstrapRunner runner = new AdminBootstrapRunner(
                new BootstrapAdminProperties("root@example.com", "a-long-admin-password"), userService);

        runner.run(null);
        runner.run(null); // idempotent: must not fail or duplicate

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findByEmail("root@example.com").orElseThrow().getRole()).isEqualTo(Role.ADMIN);

        String token = loginToken("root@example.com", "a-long-admin-password");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        Jwt jwt = NimbusJwtDecoder.withPublicKey(
                ((com.nimbusds.jose.jwk.RSAKey) JWKSet.parse(mvc.perform(get("/.well-known/jwks.json")).andReturn()
                        .getResponse().getContentAsString()).getKeys().get(0)).toRSAPublicKey()).build().decode(token);
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("ADMIN");
    }

    @Test
    void bootstrapNeverPromotesOrChangesAnExistingAccount() throws Exception {
        register(ANN); // ann@example.com as CUSTOMER

        new AdminBootstrapRunner(new BootstrapAdminProperties("ann@example.com", "a-long-admin-password"), userService)
                .run(null);

        assertThat(users.findByEmail("ann@example.com").orElseThrow().getRole()).isEqualTo(Role.CUSTOMER);
        loginToken("ann@example.com", "password123"); // original password still works
    }

    @Test
    void roleColumnRejectsUnknownValues() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into users(id,email,password_hash,full_name,role) values (gen_random_uuid(),'x@y.z','h','n','SUPERUSER')"))
                .isInstanceOf(Exception.class);
    }

    private String signedToken(UUID subject, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject.toString()).issuedAt(issuedAt).expiresAt(expiresAt).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
