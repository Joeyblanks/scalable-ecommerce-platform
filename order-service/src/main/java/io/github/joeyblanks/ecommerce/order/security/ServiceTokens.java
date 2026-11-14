package io.github.joeyblanks.ecommerce.order.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** Service tokens carry {@code client_id} (RFC 9068); user tokens never do (ADR 008). */
public final class ServiceTokens {

    private ServiceTokens() {
    }

    public static boolean isServiceToken(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim("client_id");
    }

    public static AuthorizationManager<RequestAuthorizationContext> notAServiceToken() {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) ->
                new AuthorizationDecision(!isServiceToken(authentication.get()));
    }
}
