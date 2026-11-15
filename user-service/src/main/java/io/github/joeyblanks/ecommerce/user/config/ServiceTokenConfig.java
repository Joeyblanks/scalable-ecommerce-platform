package io.github.joeyblanks.ecommerce.user.config;

import io.github.joeyblanks.ecommerce.user.security.ServiceTokens;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.github.joeyblanks.ecommerce.user.ratelimit.TokenEndpointLimits;
import io.github.joeyblanks.ecommerce.user.ratelimit.TokenEndpointRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Service-to-service tokens via the OAuth2 client_credentials grant, and nothing else.
 *
 * <ul>
 *   <li>Only {@code POST /oauth2/token} is reachable. The authorization server's other endpoints
 *       (authorize, introspection, revocation, metadata, its own JWKS) are not routed here.</li>
 *   <li>Tokens are signed by the application's existing {@code JwtEncoder} (the same RSA key), and get
 *       the same {@code iss} as customer tokens, so they verify against the one existing
 *       {@code /.well-known/jwks.json}. No JWKSource bean is defined, so no second JWKS is served.</li>
 *   <li>Customer login is untouched: it lives in the other filter chain.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ServiceTokenProperties.class)
public class ServiceTokenConfig {

    static final String TOKEN_ENDPOINT = "/oauth2/token";
    static final String AUDIENCE_SETTING = "app.audience";
    private static final Logger log = LoggerFactory.getLogger(ServiceTokenConfig.class);

    @Bean
    @Order(1) // evaluated before the customer chain, but only for the token endpoint
    public SecurityFilterChain serviceTokenEndpointChain(HttpSecurity http, TokenEndpointLimits limits) {
        http
                // First in the chain: a blocked caller costs no password hashing (ADR 011).
                .addFilterBefore(new TokenEndpointRateLimitFilter(limits), SecurityContextHolderFilter.class)
                .securityMatcher(TOKEN_ENDPOINT)
                .oauth2AuthorizationServer(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // No login page anywhere: a request without client credentials is simply 401.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(ServiceTokenProperties props) {
        Map<String, RegisteredClient> clients = new ConcurrentHashMap<>(props.enabledClients().stream()
                .map(c -> toRegisteredClient(c, props))
                .collect(Collectors.toMap(RegisteredClient::getClientId, Function.identity())));
        log.info("Service token clients enabled: {}", clients.keySet());
        return new RegisteredClientRepository() {
            /**
             * Called by the authorization server only to re-hash a configured client's secret with a
             * stronger bcrypt cost after a successful login (same secret). That is kept in memory.
             * New clients can never be registered at runtime.
             */
            @Override
            public void save(RegisteredClient registeredClient) {
                if (clients.replace(registeredClient.getClientId(), registeredClient) == null) {
                    throw new UnsupportedOperationException("Service clients are configured, not registered at runtime");
                }
            }

            @Override
            public RegisteredClient findById(String id) {
                return clients.get(id);
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                return clients.get(clientId);
            }
        };
    }

    private static RegisteredClient toRegisteredClient(ServiceTokenProperties.Client c, ServiceTokenProperties props) {
        return RegisteredClient.withId(c.clientId())
                .clientId(c.clientId())
                .clientSecret(c.secretHash()) // bcrypt hash, checked with the application's BCrypt PasswordEncoder
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS) // the only grant allowed
                .scopes(scopes -> scopes.addAll(c.scopes()))
                .clientSettings(ClientSettings.builder().setting(AUDIENCE_SETTING, c.audience()).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED) // a JWT, verifiable via the JWKS
                        .accessTokenTimeToLive(props.ttl())
                        .build())
                .build();
    }

    /**
     * Shapes service tokens: the one shared issuer, the target audience, and the RFC 9068
     * {@code client_id} claim that marks a token as a service token. Never any {@code roles}.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> serviceTokenClaims(JwtProperties jwt) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            RegisteredClient client = context.getRegisteredClient();
            String audience = client.getClientSettings().getSetting(AUDIENCE_SETTING);
            context.getClaims()
                    .issuer(jwt.issuer())
                    .audience(List.of(audience))
                    .claim(ServiceTokens.CLIENT_ID_CLAIM, client.getClientId())
                    .claims(claims -> claims.remove("roles"));
        };
    }

    /** The issuer is not taken from here (see serviceTokenClaims); required by the authorization server. */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().tokenEndpoint(TOKEN_ENDPOINT).build();
    }

    /**
     * Service tokens are self-contained JWTs that are never refreshed, introspected or revoked, so the
     * authorization server has no reason to remember them. The default in-memory store would keep
     * every issued token forever (an unbounded memory leak), so we store nothing.
     */
    @Bean
    public OAuth2AuthorizationService authorizationService() {
        return new OAuth2AuthorizationService() {
            @Override
            public void save(OAuth2Authorization authorization) {
                // intentionally not stored
            }

            @Override
            public void remove(OAuth2Authorization authorization) {
                // nothing stored
            }

            @Override
            public OAuth2Authorization findById(String id) {
                return null;
            }

            @Override
            public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
                return null;
            }
        };
    }
}
