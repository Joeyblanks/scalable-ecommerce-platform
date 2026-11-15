package io.github.joeyblanks.ecommerce.user.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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

    /**
     * Grants access only to a service token that carries {@code scope} and was issued for {@code audience}.
     * Customer and admin tokens never qualify (they have no {@code client_id}), nor does a service token
     * issued for another service or without the scope (scopes must be requested explicitly, ADR 008).
     */
    public static AuthorizationManager<RequestAuthorizationContext> serviceTokenWith(String scope, String audience) {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) ->
                new AuthorizationDecision(authentication.get() instanceof JwtAuthenticationToken auth
                        && isServiceToken(auth)
                        && hasScope(auth.getToken(), scope)
                        && auth.getToken().getAudience() != null
                        && auth.getToken().getAudience().contains(audience));
    }

    private static boolean hasScope(Jwt jwt, String scope) {
        Object claim = jwt.getClaim("scope");
        if (claim instanceof Collection<?> scopes) {
            return scopes.contains(scope);
        }
        return claim instanceof String s && Arrays.asList(s.split(" ")).contains(scope);
    }

    /** Grants access unless the caller authenticated with a service token. */
    public static AuthorizationManager<RequestAuthorizationContext> notAServiceToken() {
        return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) ->
                new AuthorizationDecision(!isServiceToken(authentication.get()));
    }
}
