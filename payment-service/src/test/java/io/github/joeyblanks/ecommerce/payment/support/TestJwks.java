package io.github.joeyblanks.ecommerce.payment.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Plays the part of user-service in integration tests: serves a real JWKS over HTTP and signs real
 * RS256 tokens, so the catalog's production jwk-set-uri decoder is exercised end to end.
 */
public final class TestJwks implements AutoCloseable {

    public static final String ISSUER = "user-service";

    private final RSAKey trustedKey = generate("trusted-key");
    private final HttpServer server;

    public TestJwks() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(trustedKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/jwks.json";
    }

    /** A valid token signed by the key the JWKS publishes. */
    public String token(String role) {
        return sign(trustedKey, ISSUER, role, Instant.now(), Instant.now().plus(Duration.ofHours(1)));
    }

    public String sign(RSAKey key, String issuer, String role, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer).subject("user-1").issuedAt(issuedAt).expiresAt(expiresAt);
        if (role != null) {
            claims.claim("roles", List.of(role));
        }
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    /** A user token for this subject (user id) with this role, as user-service issues at login. */
    public String userToken(String subject, String role) {
        return signClaims(claims -> {
            claims.subject(subject);
            if (role != null) {
                claims.claim("roles", List.of(role));
            }
        });
    }

    /** A service token as user-service's client_credentials grant issues it (ADR 008). */
    public String serviceToken(String clientId, String audience, List<String> scopes) {
        return serviceToken(trustedKey, clientId, audience, scopes);
    }

    public String serviceToken(RSAKey key, String clientId, String audience, List<String> scopes) {
        return signClaims(key, claims -> {
            claims.subject(clientId).claim("client_id", clientId);
            if (audience != null) {
                claims.audience(List.of(audience));
            }
            if (scopes != null) {
                claims.claim("scope", scopes);
            }
        });
    }

    /** Signs arbitrary claims (plus issuer and a valid lifetime) with the trusted key. */
    public String signClaims(java.util.function.Consumer<JwtClaimsSet.Builder> customizer) {
        return signClaims(trustedKey, customizer);
    }

    private String signClaims(RSAKey key, java.util.function.Consumer<JwtClaimsSet.Builder> customizer) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER).issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        customizer.accept(claims);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    public static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
