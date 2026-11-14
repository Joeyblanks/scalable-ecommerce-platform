package io.github.joeyblanks.ecommerce.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import io.github.joeyblanks.ecommerce.user.support.TestKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaKeyLoaderTest {

    private static JwtProperties props(String inline, String file) {
        return new JwtProperties(inline, file, "user-service", Duration.ofHours(1));
    }

    @Test
    void loadsBase64EncodedPem() {
        RSAKey key = RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), null));

        assertThat(key.isPrivate()).isTrue();
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
    }

    @Test
    void loadsRawPem() {
        assertThat(RsaKeyLoader.load(props(TestKeys.sharedPem(), "")).getKeyID()).isNotBlank();
    }

    @Test
    void loadsFromMountedFile(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("jwt.pem"), TestKeys.sharedPem());

        assertThat(RsaKeyLoader.load(props("", file.toString())).getKeyID())
                .isEqualTo(RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), null)).getKeyID());
    }

    @Test
    void sameKeyGivesSameKidAcrossLoads_soTokensSurviveRestarts() {
        String a = RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), null)).getKeyID();
        String b = RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), null)).getKeyID();
        String other = RsaKeyLoader.load(props(TestKeys.base64(TestKeys.pem(2048)), null)).getKeyID();

        assertThat(a).isEqualTo(b).isNotEqualTo(other);
    }

    @Test
    void publicViewHasNoPrivateParameters() {
        RSAKey pub = RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), null)).toPublicJWK();

        assertThat(pub.isPrivate()).isFalse();
        assertThat(pub.toJSONString()).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
    }

    @Test
    void rejectsNeitherOrBothSources(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("k.pem"), TestKeys.sharedPem());

        assertThatThrownBy(() -> RsaKeyLoader.load(props("", ""))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> RsaKeyLoader.load(props(null, null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RsaKeyLoader.load(props(TestKeys.sharedBase64Pem(), file.toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
    }

    @Test
    void rejectsGarbageWithoutEchoingIt() {
        String garbage = "change-me-this-is-a-placeholder";

        assertThatThrownBy(() -> RsaKeyLoader.load(props(garbage, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(garbage);
    }

    @Test
    void rejectsKeysShorterThan2048Bits() {
        assertThatThrownBy(() -> RsaKeyLoader.load(props(TestKeys.base64(TestKeys.pem(1024)), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2048");
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> RsaKeyLoader.load(props("", "/nonexistent/jwt.pem")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void propertiesToStringNeverContainsKey() {
        assertThat(props(TestKeys.sharedBase64Pem(), null).toString()).doesNotContain(TestKeys.sharedBase64Pem());
    }
}
