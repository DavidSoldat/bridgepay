package com.bridgepay.gateway.audit;

import com.bridgepay.gateway.filter.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Records audited requests (AuditAction) by ops/merchant callers, and any audited request that was denied (403).
 * Lowest precedence, so it runs nested inside Spring Security's chain: the principal is still in the context
 * after chain.doFilter returns, and 401s from the security chain never get here (no actor to name).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);

    private final AuditEntryRepository repository;

    public AuditFilter(AuditEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);

        Optional<AuditMatch> match = AuditAction.match(request);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (match.isEmpty() || !(auth instanceof JwtAuthenticationToken token)) {
            return;
        }
        String role = roleOf(token);
        int status = response.getStatus();
        if (role.equals("OTHER") && status != HttpServletResponse.SC_FORBIDDEN) {
            return;
        }
        AuditEntry entry = AuditEntry.record(match.get(), token.getToken().getSubject(),
                token.getToken().getClaimAsString("preferred_username"), role, request.getMethod(),
                request.getRequestURI(), status, response.getHeader(CorrelationIdFilter.HEADER));
        try {
            repository.save(entry);
        } catch (RuntimeException e) {
            // ponytail: fail-open - a Postgres outage drops audit rows to this log line; fail-closed or a local spool if that's not acceptable
            log.error("audit write failed: {}", entry, e);
        }
    }

    private static String roleOf(JwtAuthenticationToken token) {
        Set<String> roles = token.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        if (roles.contains("ROLE_OPS")) return "OPS";
        if (roles.contains("ROLE_MERCHANT")) return "MERCHANT";
        return "OTHER";
    }
}
