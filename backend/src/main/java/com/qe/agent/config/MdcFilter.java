package com.qe.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * MdcFilter — populates the SLF4J Mapped Diagnostic Context (MDC) with a unique
 * correlation request ID for every incoming HTTP request (#B-14).
 *
 * <p>Picks up an incoming {@code X-Request-ID} or {@code X-Correlation-ID} header if provided
 * by an upstream gateway / reverse proxy, or generates a new UUID. Echoes the ID back in the
 * HTTP response as {@code X-Request-ID} for end-to-end tracing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY_REQUEST_ID = "requestId";
    public static final String MDC_KEY_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = request.getHeader(CORRELATION_ID_HEADER);
        }
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY_REQUEST_ID, requestId);
        // Also populate traceId in MDC if not already managed by OpenTelemetry
        if (MDC.get(MDC_KEY_TRACE_ID) == null) {
            MDC.put(MDC_KEY_TRACE_ID, requestId);
        }

        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY_REQUEST_ID);
            MDC.remove(MDC_KEY_TRACE_ID);
        }
    }
}
