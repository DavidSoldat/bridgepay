package com.bridgepay.application.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Converter;
import java.util.stream.Collectors;

/**
 * OAuth2 resource server config backed by Keycloak. Active everywhere except
 * the "local" convenience profile - see LocalDevSecurityConfig. Note: method
 * security (@PreAuthorize on ops-only endpoints) is independent of the HTTP
 * filter chain and still enforces role checks even under the local profile's
 * permitAll() - there is no bypass for that, by design.
 */
@Configuration
@EnableMethodSecurity
@Profile("!local")
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)));
        return http.build();
    }

    /**
     * Keycloak puts realm roles under the "realm_access.roles" claim, which
     * Spring Security's default converter doesn't know to look at - without
     * this, @PreAuthorize("hasRole('OPS')") would never match anything.
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();
        return jwt -> {
            Collection<GrantedAuthority> authorities = new java.util.ArrayList<>(defaultConverter.convert(jwt));
            authorities.addAll(extractRealmRoles(jwt));
            return new JwtAuthenticationToken(jwt, authorities);
        };
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
