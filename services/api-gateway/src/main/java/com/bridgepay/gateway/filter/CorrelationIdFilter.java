package com.bridgepay.gateway.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Reads X-Correlation-Id if the client sent one, otherwise generates one -
 * this is a log-correlation token, not a database primary key, so plain
 * UUID.randomUUID() is correct here (UUIDv7 is for primary keys only, per
 * CLAUDE.md). Runs before RateLimitFilter so rate-limited responses still
 * carry a trace ID.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String correlationId = httpRequest.getHeader(HEADER);
        boolean generated = correlationId == null || correlationId.isBlank();
        if (generated) {
            correlationId = UUID.randomUUID().toString();
        }
        httpResponse.setHeader(HEADER, correlationId);
        MDC.put("traceId", correlationId);

        try {
            ServletRequest toForward = generated
                    ? new CorrelationIdRequestWrapper(httpRequest, correlationId)
                    : request;
            chain.doFilter(toForward, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private static final class CorrelationIdRequestWrapper extends HttpServletRequestWrapper {
        private final String correlationId;

        CorrelationIdRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(HEADER::equalsIgnoreCase)) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
