package io.github.joeyblanks.ecommerce.user.ratelimit;

import io.github.joeyblanks.ecommerce.user.config.RateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The two limits of the service-token endpoint, keyed so that one caller cannot hurt another:
 * <ul>
 *   <li><b>failed requests per caller address</b>: guessing a client secret is only possible by failing, and the
 *       address is the real connection address, never a header the caller sends;</li>
 *   <li><b>successful requests per client id</b>: a client that keeps asking for tokens (they live 5 minutes and
 *       callers cache them) is misbehaving. Only successes count, so someone failing with another service's client id
 *       can neither use up that client's budget nor lock it out.</li>
 * </ul>
 */
@Component
public class TokenEndpointLimits {

    private final AttemptLimiter failuresByAddress;
    private final AttemptLimiter successesByClient;

    public TokenEndpointLimits(RateLimitProperties props, Clock clock) {
        RateLimitProperties.Token t = props.token();
        this.failuresByAddress = new AttemptLimiter(t.maxFailuresPerAddress(), t.failureWindow(), props.maxTrackedKeys(), clock);
        this.successesByClient = new AttemptLimiter(t.maxPerClient(), t.clientWindow(), props.maxTrackedKeys(), clock);
    }

    /** @return how long this request must wait, or empty if it may proceed */
    public Optional<Duration> blockedFor(String address, String clientId) {
        Optional<Duration> byAddress = failuresByAddress.blockedFor(address);
        Optional<Duration> byClient = clientId == null ? Optional.empty() : successesByClient.blockedFor(clientId);
        if (byAddress.isPresent() && byClient.isPresent()) {
            return byAddress.get().compareTo(byClient.get()) >= 0 ? byAddress : byClient;
        }
        return byAddress.isPresent() ? byAddress : byClient;
    }

    public void recordFailure(String address) {
        failuresByAddress.record(address);
    }

    public void recordSuccess(String clientId) {
        if (clientId != null) {
            successesByClient.record(clientId);
        }
    }
}
