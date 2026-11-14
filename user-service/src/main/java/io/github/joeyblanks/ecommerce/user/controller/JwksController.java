package io.github.joeyblanks.ecommerce.user.controller;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so other services can verify tokens without any shared secret. */
@RestController
public class JwksController {

    private final Map<String, Object> publicJwks;

    public JwksController(RSAKey signingKey) {
        // toJSONObject(true) = public parameters only; the private key can never be serialised here.
        this.publicJwks = new JWKSet(signingKey).toJSONObject(true);
    }

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(publicJwks);
    }
}
