package io.github.joeyblanks.ecommerce.user.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Generates throwaway RSA keys at test time. No key material is ever committed to the repo. */
public final class TestKeys {

    /** One key shared by all Spring contexts in a test run, generated once. */
    private static final String SHARED_PEM = pem(2048);

    private TestKeys() {
    }

    public static String sharedPem() {
        return SHARED_PEM;
    }

    public static String sharedBase64Pem() {
        return base64(SHARED_PEM);
    }

    public static String base64(String pem) {
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
    }

    /** A fresh PKCS#8 PEM ("BEGIN PRIVATE KEY"), the same format {@code openssl genpkey} produces. */
    public static String pem(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            KeyPair pair = generator.generateKeyPair();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                    .encodeToString(pair.getPrivate().getEncoded());
            return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
