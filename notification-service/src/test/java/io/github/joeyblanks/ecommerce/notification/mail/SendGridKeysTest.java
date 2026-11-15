package io.github.joeyblanks.ecommerce.notification.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class SendGridKeysTest {

    public static final String KEY = "SG.abcdefghijklmnopqrstuv.wxyz0123456789ABCDEFGHIJKLMNOPQRSTUV";

    @Test
    void acceptsTheShapeOfASendGridKey() {
        assertThat(SendGridKeys.looksLikeKey(KEY)).isTrue();
        assertThat(SendGridKeys.requireKey(" " + KEY + "\n")).isEqualTo(KEY);
    }

    @Test
    void refusesAnythingElseWithoutEchoingIt() {
        for (String bad : new String[]{"sk_test_51Abcdefghijkl", "SG.", "SG.short", "sg.abcdefghijklmnopqrstuvwxyz", "SECRETVALUE1234567890123",
                "SG.has space inside 123456789012345"}) {
            assertThat(SendGridKeys.looksLikeKey(bad)).as(bad).isFalse();
            assertThatThrownBy(() -> SendGridKeys.requireKey(bad)).isInstanceOf(IllegalStateException.class)
                    .message().doesNotContain("SECRETVALUE").doesNotContain("sk_test").doesNotContain("short").doesNotContain("inside");
        }
    }

    @Test
    void aMissingKeyIsRefused() {
        assertThatThrownBy(() -> SendGridKeys.requireKey(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SendGridKeys.requireKey(null)).isInstanceOf(IllegalStateException.class);
    }
}
