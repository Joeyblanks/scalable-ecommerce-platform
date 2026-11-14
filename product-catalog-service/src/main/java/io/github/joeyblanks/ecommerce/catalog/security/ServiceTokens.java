package io.github.joeyblanks.ecommerce.catalog.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Tells service tokens apart from user tokens (ADR 008). A service token is issued by the OAuth2
 * client_credentials grant and carries a {@code client_id} claim (RFC 9068); user tokens never do.
 */
public final class ServiceTokens {

    public static final String CLIENT_ID_CLAIM = "client_id";

    private ServiceTokens() {
    }

    public static boolean isServiceToken(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim(CLIENT_ID_CLAIM);
    }

    /** For customer-facing endpoints: anyone except a caller using a service token. */
    public static AuthorizationManager<RequestAuthorizationContext> notAServiceToken() {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) ->
                new AuthorizationDecision(!isServiceToken(authentication.get()));
    }

    /** For internal endpoints: only a service token whose audience includes this service. */
    public static AuthorizationManager<RequestAuthorizationContext> serviceTokenFor(String audience) {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) -> {
            Authentication auth = authentication.get();
            boolean granted = isServiceToken(auth)
                    && ((JwtAuthenticationToken) auth).getToken().getAudience() != null
                    && ((JwtAuthenticationToken) auth).getToken().getAudience().contains(audience);
            return new AuthorizationDecision(granted);
        };
    }
}
