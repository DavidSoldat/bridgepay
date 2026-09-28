package com.bridgepay.applicant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convenience-only security config for local docker-compose use without a running Keycloak. Stubs an
 * authenticated Jwt principal on every request. When a real bearer token is present (main-app/storefront
 * attach the genuine Keycloak token), its claims are copied onto the stub unvalidated - the local profile's
 * whole point is to skip signature verification - and its realm_access.roles become granted authorities the
 * same way SecurityConfig does, so @PreAuthorize checks behave like a real call. No token at all: a fixed demo
 * subject with no authorities (so ops-only endpoints refuse it).
 */
@Configuration
@EnableMethodSecurity
@Profile("local")
public class LocalDevSecurityConfig {

    static final String LOCAL_DEV_SUBJECT = "00000000-0000-7000-8000-000000000099";
    private static final Set<String> RESERVED_TIMING_CLAIMS = Set.of("iat", "exp", "nbf");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(new StubJwtAuthenticationFilter(), AuthorizationFilter.class);
        return http.build();
    }

    private static class StubJwtAuthenticationFilter extends OncePerRequestFilter {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Map<String, Object> realClaims = extractRealClaims(request);

            Jwt.Builder builder = Jwt.withTokenValue("local-dev-token").header("alg", "none");
            Collection<GrantedAuthority> authorities;
            if (realClaims != null) {
                realClaims.forEach((key, value) -> {
                    if (!RESERVED_TIMING_CLAIMS.contains(key)) {
                        builder.claim(key, value);
                    }
                });
                authorities = extractRealmRoles(realClaims);
            } else {
                builder.claim("sub", LOCAL_DEV_SUBJECT);
                authorities = List.of();
            }
            Jwt stubJwt = builder.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();

            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(stubJwt, authorities));
            chain.doFilter(request, response);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> extractRealClaims(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                return null;
            }
            try {
                String[] parts = header.substring(7).split("\\.");
                byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64Url(parts[1]));
                Map<String, Object> claims = objectMapper.readValue(payloadBytes, Map.class);
                Object sub = claims.get("sub");
                return (sub instanceof String s && !s.isBlank()) ? claims : null;
            } catch (Exception malformedToken) {
                return null;
            }
        }

        private static Collection<GrantedAuthority> extractRealmRoles(Map<String, Object> claims) {
            if (!(claims.get("realm_access") instanceof Map<?, ?> realmAccess)
                    || !(realmAccess.get("roles") instanceof List<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toString().toUpperCase()))
                    .toList();
        }

        private static String padBase64Url(String segment) {
            int remainder = segment.length() % 4;
            return remainder == 0 ? segment : segment + "=".repeat(4 - remainder);
        }
    }
}
