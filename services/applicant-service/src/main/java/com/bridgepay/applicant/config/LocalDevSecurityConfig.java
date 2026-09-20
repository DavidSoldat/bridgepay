package com.bridgepay.applicant.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Convenience-only security config for local docker-compose use without a
 * running Keycloak. Stubs an authenticated Jwt principal (fixed demo
 * subject, no granted authorities) on every request so endpoints reading
 * @AuthenticationPrincipal Jwt behave like a real authenticated call instead
 * of NPE-ing on a null principal - permitAll() alone leaves the security
 * context empty, it does not populate one.
 */
@Configuration
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
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Jwt stubJwt = Jwt.withTokenValue("local-dev-token")
                    .header("alg", "none")
                    .claim("sub", LOCAL_DEV_SUBJECT)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(stubJwt, List.of()));
            chain.doFilter(request, response);
        }
    }
}
