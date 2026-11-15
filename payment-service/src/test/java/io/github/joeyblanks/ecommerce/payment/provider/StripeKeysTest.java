package io.github.joeyblanks.ecommerce.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StripeKeysTest {

    @Test
    void acceptsOnlyTestKeys() {
        assertThat(StripeKeys.isTestKey("sk_test_51Abcdefghijkl")).isTrue();
        assertThat(StripeKeys.isTestKey("rk_test_51Abcdefghijkl")).isTrue();
        assertThat(StripeKeys.isTestKey("sk_live_51Abcdefghijkl")).isFalse();
        assertThat(StripeKeys.isTestKey("rk_live_51Abcdefghijkl")).isFalse();
        assertThat(StripeKeys.isTestKey("pk_test_51Abcdefghijkl")).isFalse(); // publishable keys cannot charge
        assertThat(StripeKeys.isTestKey("sk_test_")).isFalse();
        assertThat(StripeKeys.isTestKey("whsec_abc")).isFalse();
        assertThat(StripeKeys.isTestKey(null)).isFalse();
    }

    @Test
    void aLiveKeyIsRefusedAndNeverEchoed() {
        String live = "sk_live_SUPERSECRETVALUE123456";
        assertThatThrownBy(() -> StripeKeys.requireTestKey(live))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEST key")
                .message().doesNotContain("SUPERSECRET").doesNotContain("sk_live");
    }

    @Test
    void aMissingKeyIsRefused() {
        assertThatThrownBy(() -> StripeKeys.requireTestKey(" ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StripeKeys.requireTestKey(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTestKeyIsReturnedTrimmed() {
        assertThat(StripeKeys.requireTestKey(" sk_test_51Abcdefghijkl\n")).isEqualTo("sk_test_51Abcdefghijkl");
    }
}
