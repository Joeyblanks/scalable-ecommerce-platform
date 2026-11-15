package io.github.joeyblanks.ecommerce.user.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In front of {@code POST /oauth2/token} only (see ServiceTokenConfig; it is deliberately not a servlet-filter bean,
 * so it cannot run anywhere else). Refuses with 429 + Retry-After when a limit of {@link TokenEndpointLimits} is
 * reached, then watches the outcome: 200 counts for the client id, 400/401 count as a failure for the caller's address.
 *
 * <p>The address is {@code request.getRemoteAddr()}: the real connection. {@code X-Forwarded-For} and similar headers are
 * never read, because a caller can send anything in them.
 */
public class TokenEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpointRateLimitFilter.class);

    private final TokenEndpointLimits limits;

    public TokenEndpointRateLimitFilter(TokenEndpointLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = request.getRemoteAddr();
        String clientId = clientIdOf(request.getHeader("Authorization"));
        Optional<Duration> wait = limits.blockedFor(address, clientId);
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            log.warn("Token endpoint rate limit reached for {} (client {}): retry in {}s", address, clientId, seconds);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(seconds));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"too_many_requests\",\"error_description\":\"Too many requests. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
        int status = response.getStatus();
        if (status == 200) {
            limits.recordSuccess(clientId);
        } else if (status == 400 || status == 401) {
            limits.recordFailure(address);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    /** The client id of HTTP Basic credentials (RFC 6749 section 2.3.1), or null. Not authenticated here. */
    static String clientIdOf(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            String id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            return id.isBlank() || id.length() > 200 ? null : id;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
