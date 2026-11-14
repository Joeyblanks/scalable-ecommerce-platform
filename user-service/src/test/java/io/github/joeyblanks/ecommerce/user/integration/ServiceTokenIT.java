package io.github.joeyblanks.ecommerce.user.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.github.joeyblanks.ecommerce.user.repository.UserRepository;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Service-to-service tokens (OAuth2 client_credentials) on the real application: one issuer, one
 * JWKS, nothing else from the authorization server reachable, and tokens kept apart from customer
 * tokens. Run by Failsafe; requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ServiceTokenIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static final String ORDER_SECRET = "order-service-test-secret";
    static final String REPORTING_SECRET = "reporting-service-test-secret";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        registry.add("app.jwt.private-key", TestKeys::sharedBase64Pem);
        registry.add("app.jwt.issuer", () -> "user-service");
        registry.add("app.service-tokens.clients[0].client-id", () -> "order-service");
        registry.add("app.service-tokens.clients[0].secret-hash", () -> bcrypt.encode(ORDER_SECRET));
        registry.add("app.service-tokens.clients[0].scopes[0]", () -> "stock:reserve");
        registry.add("app.service-tokens.clients[0].audience", () -> "product-catalog-service");
        registry.add("app.service-tokens.clients[1].client-id", () -> "reporting-service");
        registry.add("app.service-tokens.clients[1].secret-hash", () -> bcrypt.encode(REPORTING_SECRET));
        registry.add("app.service-tokens.clients[1].scopes[0]", () -> "reports:read");
        registry.add("app.service-tokens.clients[1].scopes[1]", () -> "reports:write");
        registry.add("app.service-tokens.clients[1].audience", () -> "reporting");
        // configured but without a secret hash = disabled
        registry.add("app.service-tokens.clients[2].client-id", () -> "disabled-service");
        registry.add("app.service-tokens.clients[2].secret-hash", () -> "");
        registry.add("app.service-tokens.clients[2].scopes[0]", () -> "stock:reserve");
        registry.add("app.service-tokens.clients[2].audience", () -> "product-catalog-service");
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;

    private static final MediaType FORM = MediaType.APPLICATION_FORM_URLENCODED;
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @BeforeEach
    void clean() {
        users.deleteAll();
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder tokenRequest() {
        return post("/oauth2/token").contentType(FORM).param("grant_type", "client_credentials");
    }

    private String serviceToken(String clientId, String secret, String scope) throws Exception {
        String body = mvc.perform(tokenRequest().param("scope", scope).with(httpBasic(clientId, secret)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private String customerToken(String email) throws Exception {
        mvc.perform(post("/api/users/register").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"fullName\":\"C\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/users/login").contentType(JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** Verifies a token exactly like another service would: with the one published JWKS only. */
    private Jwt verifyWithPublishedJwks(String token) throws Exception {
        String jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        RSAKey key = (RSAKey) JWKSet.parse(jwks).getKeys().get(0);
        return NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(token);
    }

    private static String kidOf(String token) throws Exception {
        return com.nimbusds.jwt.SignedJWT.parse(token).getHeader().getKeyID();
    }

    // ---------- issuing ----------

    @Test
    void issuesAShortLivedServiceTokenThatVerifiesAgainstTheOneJwks() throws Exception {
        String body = mvc.perform(tokenRequest().param("scope", "stock:reserve").with(httpBasic("order-service", ORDER_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("stock:reserve"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        int expiresIn = JsonPath.read(body, "$.expires_in");

        Jwt jwt = verifyWithPublishedJwks(token);

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.<String>getClaim("iss")).isEqualTo("user-service");
        assertThat(jwt.getSubject()).isEqualTo("order-service");
        assertThat(jwt.<String>getClaim("client_id")).isEqualTo("order-service");
        assertThat(jwt.getAudience()).containsExactly("product-catalog-service");
        assertThat(jwt.<List<String>>getClaim("scope")).containsExactly("stock:reserve");
        assertThat(jwt.hasClaim("roles")).as("a service token never carries user roles").isFalse();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(expiresIn).isBetween(290, 300);
    }

    @Test
    void serviceAndCustomerTokensShareTheIssuerAndSigningKey_butAreDistinguishable() throws Exception {
        String service = serviceToken("order-service", ORDER_SECRET, "stock:reserve");
        String customer = customerToken("ann@example.com");

        Jwt s = verifyWithPublishedJwks(service);
        Jwt c = verifyWithPublishedJwks(customer);

        assertThat(s.<String>getClaim("iss")).isEqualTo(c.<String>getClaim("iss"));
        assertThat(kidOf(service)).isEqualTo(kidOf(customer));
        assertThat(c.hasClaim("client_id")).as("customer tokens never look like service tokens").isFalse();
        assertThat(c.hasClaim("scope")).isFalse();
        assertThat(c.<List<String>>getClaim("roles")).containsExactly("CUSTOMER");
    }

    @Test
    void aClientMayRequestASubsetOfItsScopesButNothingElse() throws Exception {
        mvc.perform(tokenRequest().param("scope", "reports:read").with(httpBasic("reporting-service", REPORTING_SECRET)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("reports:read"));
        mvc.perform(tokenRequest().param("scope", "stock:reserve").with(httpBasic("reporting-service", REPORTING_SECRET)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(tokenRequest().param("scope", "admin").with(httpBasic("order-service", ORDER_SECRET)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void scopesMustBeRequestedExplicitly_aTokenRequestedWithoutScopeCarriesNone() throws Exception {
        String body = mvc.perform(tokenRequest().with(httpBasic("order-service", ORDER_SECRET)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        Jwt jwt = verifyWithPublishedJwks(JsonPath.read(body, "$.access_token"));

        assertThat(jwt.hasClaim("scope")).as("least privilege: nothing granted unless asked for").isFalse();
        assertThat(jwt.<String>getClaim("client_id")).isEqualTo("order-service"); // still a service token
    }

    @Test
    void eachClientGetsItsOwnAudience() throws Exception {
        Jwt jwt = verifyWithPublishedJwks(serviceToken("reporting-service", REPORTING_SECRET, "reports:read reports:write"));

        assertThat(jwt.getAudience()).containsExactly("reporting");
        assertThat(jwt.<List<String>>getClaim("scope")).containsExactlyInAnyOrder("reports:read", "reports:write");
    }

    // ---------- client authentication ----------

    @Test
    void wrongUnknownDisabledOrMissingClientCredentialsAre401() throws Exception {
        mvc.perform(tokenRequest().with(httpBasic("order-service", "wrong"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
        mvc.perform(tokenRequest().with(httpBasic("nobody", ORDER_SECRET))).andExpect(status().isUnauthorized());
        // an empty secret is a malformed client authentication: 400 invalid_request (RFC 6749), no token
        mvc.perform(tokenRequest().with(httpBasic("disabled-service", ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request")).andExpect(jsonPath("$.access_token").doesNotExist());
        mvc.perform(tokenRequest().with(httpBasic("disabled-service", "anything"))).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest()).andExpect(status().isUnauthorized());
        // a secret in the form body is not an accepted client authentication method
        mvc.perform(tokenRequest().param("client_id", "order-service").param("client_secret", ORDER_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCredentialsAndAnyBearerTokenAreRefusedAtTheTokenEndpoint() throws Exception {
        String customer = customerToken("bob@example.com");
        String service = serviceToken("order-service", ORDER_SECRET, "stock:reserve");

        mvc.perform(tokenRequest().with(httpBasic("bob@example.com", "password123"))).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest().header("Authorization", "Bearer " + customer)).andExpect(status().isUnauthorized());
        mvc.perform(tokenRequest().header("Authorization", "Bearer " + service)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheClientCredentialsGrantIsSupported() throws Exception {
        for (String grant : new String[] {"password", "authorization_code", "refresh_token",
                "urn:ietf:params:oauth:grant-type:device_code", "urn:ietf:params:oauth:grant-type:token-exchange", "bogus"}) {
            MockHttpServletResponse response = mvc.perform(post("/oauth2/token").contentType(FORM)
                            .param("grant_type", grant).param("code", "x").param("refresh_token", "x")
                            .param("device_code", "x").param("username", "a").param("password", "b")
                            .with(httpBasic("order-service", ORDER_SECRET)))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as("grant %s", grant).isEqualTo(400);
            assertThat(response.getContentAsString()).doesNotContain("access_token");
        }
    }

    // ---------- one issuer, one JWKS, nothing else exposed ----------

    @Test
    void noOtherAuthorizationServerEndpointIsReachable() throws Exception {
        MockHttpServletRequestBuilder[] probes = {
                get("/oauth2/jwks"),
                get("/.well-known/oauth-authorization-server"),
                get("/.well-known/openid-configuration"),
                get("/oauth2/authorize").param("response_type", "code").param("client_id", "order-service"),
                post("/oauth2/introspect").contentType(FORM).param("token", "x").with(httpBasic("order-service", ORDER_SECRET)),
                post("/oauth2/revoke").contentType(FORM).param("token", "x").with(httpBasic("order-service", ORDER_SECRET)),
                post("/oauth2/device_authorization").contentType(FORM).with(httpBasic("order-service", ORDER_SECRET)),
                post("/oauth2/par").contentType(FORM).with(httpBasic("order-service", ORDER_SECRET)),
                post("/connect/register").contentType(JSON).content("{}"),
                get("/userinfo"),
                get("/login"),
                get("/oauth2/token")};
        for (MockHttpServletRequestBuilder probe : probes) {
            MockHttpServletResponse response = mvc.perform(probe).andReturn().getResponse();
            String what = response.getStatus() + " " + response.getContentAsString();
            assertThat(response.getStatus()).as(what).isGreaterThanOrEqualTo(400);
            assertThat(response.getContentAsString()).as(what).doesNotContain("\"keys\"").doesNotContain("issuer");
        }
    }

    @Test
    void theOnlyJwksIsTheExistingOneAndItHasOneKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1));
    }

    // ---------- separation: service tokens refused on customer endpoints ----------

    @Test
    void serviceTokensAreRefusedOnEveryCustomerFacingEndpoint() throws Exception {
        String customer = customerToken("carol@example.com");
        String service = "Bearer " + serviceToken("order-service", ORDER_SECRET, "stock:reserve");

        mvc.perform(get("/api/users/me").header("Authorization", service)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users/register").header("Authorization", service).contentType(JSON)
                        .content("{\"email\":\"svc@example.com\",\"password\":\"password123\",\"fullName\":\"S\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/login").header("Authorization", service).contentType(JSON)
                        .content("{\"email\":\"carol@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        assertThat(users.findByEmail("svc@example.com")).as("refused registration created nothing").isEmpty();

        // the same calls with the customer's own token / no token behave as before
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + customer)).andExpect(status().isOk());
    }

    @Test
    void servicesCanStillReadTheJwksAndHealth() throws Exception {
        String service = "Bearer " + serviceToken("order-service", ORDER_SECRET, "stock:reserve");

        mvc.perform(get("/.well-known/jwks.json").header("Authorization", service)).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").header("Authorization", service)).andExpect(status().isOk());
    }
}
