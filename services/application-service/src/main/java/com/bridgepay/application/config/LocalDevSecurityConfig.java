package com.bridgepay.application.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Convenience-only security config for local docker-compose use without a
 * running Keycloak. Stubs an authenticated Jwt principal on every request so
 * endpoints reading @AuthenticationPrincipal Jwt behave like a real
 * authenticated call instead of NPE-ing on a null principal - permitAll()
 * alone leaves the security context empty, it does not populate one. When a
 * real bearer token is present (main-app/storefront both attach the genuine
 * token Keycloak issued), its "sub" claim is used as-is, unvalidated - local
 * profile's whole point is to skip signature verification - so different
 * real logins get different identities instead of collapsing onto one fixed
 * demo subject. Falls back to a fixed demo subject when no token is present
 * at all (plain curl testing, etc). Note that @PreAuthorize-protected (ops)
 * endpoints still reject requests here, since the stub never carries granted
 * authorities and method security is independent of this permissive filter
 * chain - that part is unchanged and still by design.
 */
@Configuration
@EnableMethodSecurity
@Profile("local")
public class LocalDevSecurityConfig {

    static final String LOCAL_DEV_SUBJECT = "00000000-0000-7000-8000-000000000099";

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
            Jwt stubJwt = Jwt.withTokenValue("local-dev-token")
                    .header("alg", "none")
                    .claim("sub", resolveSubject(request))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(stubJwt, List.of()));
            chain.doFilter(request, response);
        }

        private String resolveSubject(HttpServletRequest request) {
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                return LOCAL_DEV_SUBJECT;
            }
            try {
                String[] parts = header.substring(7).split("\\.");
                byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64Url(parts[1]));
                JsonNode payload = objectMapper.readTree(payloadBytes);
                String sub = payload.path("sub").asString(null);
                return (sub == null || sub.isBlank()) ? LOCAL_DEV_SUBJECT : sub;
            } catch (Exception malformedToken) {
                return LOCAL_DEV_SUBJECT;
            }
        }

        private static String padBase64Url(String segment) {
            int remainder = segment.length() % 4;
            return remainder == 0 ? segment : segment + "=".repeat(4 - remainder);
        }
    }
}
