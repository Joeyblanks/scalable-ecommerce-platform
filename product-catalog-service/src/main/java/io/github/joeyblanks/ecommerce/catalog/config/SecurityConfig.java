package io.github.joeyblanks.ecommerce.catalog.config;

import io.github.joeyblanks.ecommerce.catalog.security.ServiceTokens;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter,
                                                   SecurityProperties props) {
        http
                // Stateless bearer-token API: no cookies/sessions, so CSRF protection is not needed.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Internal, service-to-service only (ADR 008): a service token for this audience
                        // with the stock:reserve scope. User and ADMIN tokens -> 403, no token -> 401.
                        .requestMatchers("/internal/**").access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                ServiceTokens.serviceTokenFor(props.audience()),
                                AuthorityAuthorizationManager.hasAuthority("SCOPE_stock:reserve")))
                        // Public catalog reads, for anonymous callers and users; never for service tokens.
                        .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**")
                                .access(ServiceTokens.notAServiceToken())
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Every other method on the catalog (POST/PUT/DELETE/...) is ADMIN only:
                        // 401 without a valid token, 403 for an authenticated non-admin or a service.
                        .requestMatchers("/api/products/**", "/api/categories/**").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"), ServiceTokens.notAServiceToken()))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    /** Maps "roles" (user tokens, e.g. ["ADMIN"] -> ROLE_ADMIN) and "scope" (service tokens -> SCOPE_*). */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter(); // "scope" -> SCOPE_*
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> all = new ArrayList<>(roles.convert(jwt));
            all.addAll(scopes.convert(jwt));
            return all;
        });
        return converter;
    }

    /**
     * Verifies RS256 signatures with the issuer's JWKS (selected by "kid"), plus expiry and issuer.
     * Keys are fetched lazily and cached, so this service starts even if user-service is down.
     */
    @Bean
    public JwtDecoder jwtDecoder(SecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
