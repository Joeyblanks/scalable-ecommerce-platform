package io.github.joeyblanks.ecommerce.user.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Loads the RSA signing key from configuration. The public half is derived from the private key,
 * so there is a single secret to manage. The {@code kid} is the RFC 7638 thumbprint of the public
 * key: it is identical on every restart for the same key and changes automatically on rotation.
 *
 * <p>Error messages deliberately never include any key material.
 */
public final class RsaKeyLoader {

    static final int MIN_KEY_BITS = 2048;

    private RsaKeyLoader() {
    }

    public static RSAKey load(JwtProperties props) {
        boolean inline = hasText(props.privateKey());
        boolean file = hasText(props.privateKeyFile());
        if (inline == file) {
            throw new IllegalStateException("Configure exactly one of JWT_PRIVATE_KEY (base64 PKCS#8 PEM) "
                    + "or JWT_PRIVATE_KEY_FILE (path to a PKCS#8 PEM file); see the README for how to generate a key");
        }
        String content;
        if (inline) {
            content = props.privateKey();
        } else {
            try {
                content = Files.readString(Path.of(props.privateKeyFile()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read JWT_PRIVATE_KEY_FILE: " + props.privateKeyFile(), e);
            }
        }
        return parse(content);
    }

    static RSAKey parse(String content) {
        try {
            String trimmed = content.trim();
            String pem = trimmed.startsWith("-----BEGIN")
                    ? trimmed
                    : new String(Base64.getMimeDecoder().decode(trimmed), StandardCharsets.UTF_8);
            if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
                throw new IllegalStateException(
                        "The JWT private key must be an unencrypted PKCS#8 PEM ('BEGIN PRIVATE KEY')");
            }
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);

            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof RSAPrivateCrtKey privateKey)) {
                throw new IllegalStateException("The JWT private key must be an RSA key");
            }
            if (privateKey.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException("The JWT RSA key must be at least " + MIN_KEY_BITS + " bits");
            }
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));

            RSAKey unnamed = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            return new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
        } catch (IllegalArgumentException | GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("The JWT private key is not a valid RSA PKCS#8 PEM "
                    + "(expected the PEM text, or its base64 encoding)");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
