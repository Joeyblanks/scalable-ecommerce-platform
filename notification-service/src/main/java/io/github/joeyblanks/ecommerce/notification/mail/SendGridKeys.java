package io.github.joeyblanks.ecommerce.notification.mail;

/**
 * Checks the shape of a SendGrid API key (they start with "SG."). The message never contains the key, so it
 * cannot leak into logs. SendGrid has no separate test-mode keys: use {@code SENDGRID_SANDBOX=true} to have
 * SendGrid validate requests without delivering anything.
 */
public final class SendGridKeys {

    private SendGridKeys() {
    }

    public static boolean looksLikeKey(String key) {
        return key != null && key.startsWith("SG.") && key.length() >= 20 && key.chars().noneMatch(Character::isWhitespace);
    }

    /** Returns the trimmed key, or throws without echoing it. */
    public static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("The SendGrid sender is selected but SENDGRID_API_KEY is not set.");
        }
        if (!looksLikeKey(key.trim())) {
            throw new IllegalStateException("SENDGRID_API_KEY does not look like a SendGrid API key (it should start with \"SG.\"). "
                    + "Refusing to start.");
        }
        return key.trim();
    }
}
