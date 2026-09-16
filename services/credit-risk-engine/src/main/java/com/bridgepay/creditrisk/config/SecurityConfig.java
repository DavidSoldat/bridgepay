package com.bridgepay.creditrisk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every endpoint this service exposes is internal-only (see the API contracts
 * section of the spec): plain ClusterIP, no Ingress, never routed through the
 * gateway. Network isolation is the actual access control here, not JWT - so
 * unlike the public-facing services, /internal/** is permitAll rather than
 * authenticated(). This still wires the real JwtAuthenticationConverter
 * (realm_access.roles) so the filter chain is ready the moment any endpoint
 * here needs a role check. Active everywhere except the "local" profile - see
 * LocalDevSecurityConfig.
 */
@Configuration
@EnableMethodSecurity
@Profile("!local")
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));
        return http.build();
    }

    /**
     * Keycloak puts realm roles under the "realm_access.roles" claim, which
     * Spring Security's default converter doesn't know to look at - see
     * application-service's SecurityConfig for the reference implementation.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(defaultConverter.convert(jwt));
            authorities.addAll(extractRealmRoles(jwt));
            return authorities;
        });
        return converter;
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()))
                .collect(Collectors.toList());
    }
}
