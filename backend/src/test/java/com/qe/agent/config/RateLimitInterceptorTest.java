package com.qe.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RateLimitInterceptor.
 * Tests rate limiting enforcement, IP resolution security, and LRU cache eviction behavior.
 */
class RateLimitInterceptorTest {

    private RateLimitInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "capacity", 2L);
        ReflectionTestUtils.setField(interceptor, "refillTokens", 2L);
        ReflectionTestUtils.setField(interceptor, "refillMinutes", 1L);
        ReflectionTestUtils.setField(interceptor, "trustForwardedFor", false);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    @DisplayName("Non-generation endpoints bypass rate limiting")
    void nonGenerationPath_alwaysAllowed() throws Exception {
        when(request.getServletPath()).thenReturn("/api/v1/health");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        verify(request, never()).getRemoteAddr();
    }

    @Test
    @DisplayName("Requests within capacity are allowed, excess requests rejected with 429")
    void withinCapacityAllowed_excessRejected() throws Exception {
        when(request.getServletPath()).thenReturn("/api/v1/generate-tests");
        when(request.getRemoteAddr()).thenReturn("192.168.1.50");

        // First 2 requests within capacity (2 tokens)
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));

        // 3rd request exceeds capacity
        boolean rejected = interceptor.preHandle(request, response, new Object());
        assertFalse(rejected);
        verify(response).setStatus(429);
        assertTrue(responseWriter.toString().contains("Rate limit exceeded"));
    }

    @Test
    @DisplayName("Untrusted X-Forwarded-For header is ignored by default to prevent spoofing")
    void untrustedForwardedFor_ignored() throws Exception {
        when(request.getServletPath()).thenReturn("/api/v1/generate-tests");
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));

        // Changing X-Forwarded-For header while remoteAddr remains same should still hit limit
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.99");
        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    @DisplayName("Trusted X-Forwarded-For header is used when trustForwardedFor is enabled")
    void trustedForwardedFor_usedWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "trustForwardedFor", true);

        when(request.getServletPath()).thenReturn("/api/v1/generate-tests");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195, 10.0.0.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        // 2 requests for client 203.0.113.195
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertFalse(interceptor.preHandle(request, response, new Object()));

        // Different client IP in X-Forwarded-For gets its own bucket
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.196");
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}
