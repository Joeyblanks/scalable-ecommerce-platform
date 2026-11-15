package io.github.joeyblanks.ecommerce.user.ratelimit;

import io.github.joeyblanks.ecommerce.user.config.RateLimitProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits failed logins per account, whatever the caller's address: attackers rotate addresses, so a per-IP limit
 * alone does not protect one account. The key is the normalized email whether or not the account exists, so the
 * answer does not reveal which emails are registered.
 *
 * <p>The trade-off, accepted and documented in ADR 011: someone who knows an email can send bad passwords and keep
 * that account's logins blocked for up to the window. It is temporary, it needs continuous traffic, the blocked
 * login path is cheap (no password hashing), and the alternatives (a permanent lock or a per-account CAPTCHA) are
 * worse for this system.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class LoginThrottle {

    private final AttemptLimiter failures;

    public LoginThrottle(RateLimitProperties props, Clock clock) {
        this.failures = new AttemptLimiter(props.login().maxFailures(), props.login().window(), props.maxTrackedKeys(), clock);
    }

    /** @throws TooManyAttemptsException if this account has had too many failed logins recently */
    public void check(String normalizedEmail) {
        failures.blockedFor(normalizedEmail).ifPresent(wait -> {
            throw new TooManyAttemptsException("Too many failed login attempts. Try again later.", wait);
        });
    }

    public void recordFailure(String normalizedEmail) {
        failures.record(normalizedEmail);
    }

    public void recordSuccess(String normalizedEmail) {
        failures.reset(normalizedEmail);
    }
}
