package com.qe.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SecurityConfig API-key filter.
 * Validates header authentication, query param fallback for EventSource, and unauthorized rejection.
 */
class SecurityConfigTest {

    private SecurityConfig securityConfig;
    private OncePerRequestFilter apiKeyFilter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        securityConfig = new SecurityConfig();
        ReflectionTestUtils.setField(securityConfig, "rawApiKeys", "prod-key-1,prod-key-2");

        apiKeyFilter = (OncePerRequestFilter) ReflectionTestUtils.invokeMethod(securityConfig, "apiKeyFilter");

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    @DisplayName("Request with valid X-API-Key header passes filterChain with authenticated principal")
    void validApiKeyHeader_authenticatesAndContinues() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("prod-key-1");

        ReflectionTestUtils.invokeMethod(apiKeyFilter, "doFilterInternal", request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    @DisplayName("Request with missing header but valid apiKey query parameter passes filterChain")
    void validApiKeyQueryParam_authenticatesAndContinues() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("apiKey")).thenReturn("prod-key-2");

        ReflectionTestUtils.invokeMethod(apiKeyFilter, "doFilterInternal", request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    @DisplayName("Request with missing or invalid key is rejected with HTTP 401")
    void invalidApiKey_rejectedWith401() throws Exception {
        when(request.getHeader("X-API-Key")).thenReturn("invalid-key");
        when(request.getParameter("apiKey")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        ReflectionTestUtils.invokeMethod(apiKeyFilter, "doFilterInternal", request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(401);
        assertTrue(responseWriter.toString().contains("Unauthorized"));
    }

    @Test
    @DisplayName("Public endpoints and WebSocket paths should not be filtered")
    void shouldNotFilter_publicAndWsPaths() {
        when(request.getServletPath()).thenReturn("/api/v1/health");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(apiKeyFilter, "shouldNotFilter", request));

        when(request.getServletPath()).thenReturn("/actuator/health");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(apiKeyFilter, "shouldNotFilter", request));

        when(request.getServletPath()).thenReturn("/ws/info");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(apiKeyFilter, "shouldNotFilter", request));

        when(request.getServletPath()).thenReturn("/ws-native");
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(apiKeyFilter, "shouldNotFilter", request));

        when(request.getServletPath()).thenReturn("/api/v1/generate-tests");
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(apiKeyFilter, "shouldNotFilter", request));
    }
}
