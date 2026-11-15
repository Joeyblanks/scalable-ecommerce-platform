package io.github.joeyblanks.ecommerce.payment.provider;

/**
 * Guards against a live Stripe key ever being used. Only test-mode keys are accepted. The message never
 * contains the key (not even a prefix of a refused one), so it cannot leak into logs.
 */
public final class StripeKeys {

    private StripeKeys() {
    }

    public static boolean isTestKey(String key) {
        return key != null && (key.startsWith("sk_test_") || key.startsWith("rk_test_")) && key.length() > 12;
    }

    /** Returns the key if it is a Stripe test key, otherwise throws without echoing it. */
    public static String requireTestKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("The Stripe provider is selected but STRIPE_API_KEY is not set.");
        }
        if (!isTestKey(key.trim())) {
            throw new IllegalStateException("STRIPE_API_KEY is not a Stripe TEST key (sk_test_... or rk_test_...). "
                    + "This service only ever talks to Stripe test mode; refusing to start.");
        }
        return key.trim();
    }
}
