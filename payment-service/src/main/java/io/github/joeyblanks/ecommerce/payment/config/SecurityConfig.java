package io.github.joeyblanks.ecommerce.payment.config;

import io.github.joeyblanks.ecommerce.payment.security.ServiceTokens;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Every payment endpoint is customer-facing: a user token is required, a service token
                        // is refused (403). A payment is only ever shown to the owner of its order.
                        .requestMatchers("/api/**").access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(), ServiceTokens.notAServiceToken()))
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(AppProperties.Security props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
