package com.bridgepay.gateway.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One global Resilience4j core RateLimiter (no Spring integration module -
 * same reason as every other Resilience4j use in this project: no confirmed
 * Boot 4-compatible annotation-based module exists). Global rather than
 * per-client-IP: a single gateway replica per spec section 13 doesn't
 * justify a keyed-limiter registry.
 * ponytail: global rate limit, switch to a per-client-IP keyed
 * RateLimiterRegistry if abuse/noisy-neighbor traffic ever shows up.
 *
 * Runs after CorrelationIdFilter (higher Order value = lower precedence), so
 * a 429 response still carries a real trace ID in its hand-written body -
 * this filter runs before DispatcherServlet, so GlobalExceptionHandler
 * cannot shape this response; the JSON is written directly instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter implements Filter {

    private final RateLimiter rateLimiter;

    public RateLimitFilter(@Value("${gateway.rate-limit.requests-per-second:50}") int requestsPerSecond) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(requestsPerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.rateLimiter = RateLimiterRegistry.of(config).rateLimiter("api-gateway");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = ((HttpServletRequest) request).getRequestURI();
        if (path.startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }

        if (rateLimiter.acquirePermission()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json");
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        httpResponse.getWriter().write("""
                {"error":"RATE_LIMITED","message":"Too many requests","traceId":"%s","timestamp":"%s"}"""
                .formatted(traceId, Instant.now()));
    }
}
