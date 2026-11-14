package io.github.joeyblanks.ecommerce.user.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Tells service tokens apart from user tokens. A service token is one issued by the OAuth2
 * client_credentials grant: it carries a {@code client_id} claim (RFC 9068). Tokens issued at
 * customer login never do.
 */
public final class ServiceTokens {

    public static final String CLIENT_ID_CLAIM = "client_id";

    private ServiceTokens() {
    }

    public static boolean isServiceToken(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim(CLIENT_ID_CLAIM);
    }

    /** Grants access unless the caller authenticated with a service token. */
    public static AuthorizationManager<RequestAuthorizationContext> notAServiceToken() {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) ->
                new AuthorizationDecision(!isServiceToken(authentication.get()));
    }
}
