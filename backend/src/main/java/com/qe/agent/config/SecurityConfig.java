package com.qe.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityConfig — API-key-based authentication for all protected endpoints.
 *
 * <p>Activation: set {@code qe.security.enabled=true} in {@code application.properties}.
 * When disabled (the default), Spring Security is not loaded and the app behaves as before.
 *
 * <p>When enabled, every request to {@code /api/**} must carry the header:
 * <pre>X-API-Key: &lt;value of qe.security.api-keys&gt;</pre>
 *
 * <p>The following paths are always public (no key required):
 * <ul>
 *   <li>{@code /api/v1/health}</li>
 *   <li>{@code /actuator/health}</li>
 *   <li>{@code /actuator/prometheus}</li>
 * </ul>
 *
 * <p><b>Production usage:</b>
 * <ol>
 *   <li>Set {@code qe.security.enabled=true}</li>
 *   <li>Set {@code qe.security.api-keys} to a comma-separated list of strong random secrets
 *       (minimum 32 characters each, generated with {@code openssl rand -hex 32})</li>
 *   <li>Never commit the actual key values to source control — use environment variables or
 *       a secrets manager to inject them at runtime.</li>
 * </ol>
 */
@Slf4j
@Configuration
@EnableWebSecurity
@SuppressWarnings("null")
public class SecurityConfig {

    /** Header name clients must send. */
    public static final String API_KEY_HEADER = "X-API-Key";

    @Value("${qe.security.api-keys:}")
    private String rawApiKeys;

    // -------------------------------------------------------------------------
    // Security filter chains
    // -------------------------------------------------------------------------

    /**
     * Default chain for local development and test slices when security is disabled.
     * Disables CSRF and permits all requests so local dev and tests work out of the box.
     */
    @Bean
    @ConditionalOnProperty(name = "qe.security.enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        log.info("[Security] API key authentication is DISABLED (qe.security.enabled=false) — all requests permitted");
        return http.build();
    }

    /**
     * Production chain activated when qe.security.enabled=true.
     * Enforces API-key header validation on protected endpoints.
     */
    @Bean
    @ConditionalOnProperty(name = "qe.security.enabled", havingValue = "true")
    public SecurityFilterChain apiKeyFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless API, no browser session
            .csrf(csrf -> csrf.disable())
            // Stateless: no HTTP session created or used
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Public endpoints; everything else requires a valid API key
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/health",
                    "/actuator/health",
                    "/actuator/prometheus",
                    "/ws/**",
                    "/ws-native/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            // Plug in our API-key filter before the standard username/password filter
            .addFilterBefore(apiKeyFilter(), UsernamePasswordAuthenticationFilter.class);

        log.warn("[Security] API key authentication is ENABLED — all /api/** calls require X-API-Key header");
        return http.build();
    }

    // -------------------------------------------------------------------------
    // API-key filter
    // -------------------------------------------------------------------------

    private OncePerRequestFilter apiKeyFilter() {
        Set<String> validKeys = Arrays.stream(rawApiKeys.split(","))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .collect(Collectors.toSet());

        if (validKeys.isEmpty()) {
            log.error("[Security] qe.security.enabled=true but qe.security.api-keys is empty! " +
                      "All authenticated requests will be rejected until at least one key is configured.");
        }

        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(@NonNull HttpServletRequest request,
                                            @NonNull HttpServletResponse response,
                                            @NonNull FilterChain filterChain)
                    throws ServletException, IOException {

                String providedKey = request.getHeader(API_KEY_HEADER);
                if (providedKey == null || providedKey.isBlank()) {
                    providedKey = request.getParameter("apiKey");
                    if (providedKey == null || providedKey.isBlank()) {
                        providedKey = request.getParameter("api_key");
                    }
                }

                boolean matches = false;
                if (providedKey != null && !providedKey.isBlank() && !validKeys.isEmpty()) {
                    byte[] providedBytes = providedKey.getBytes(StandardCharsets.UTF_8);
                    for (String key : validKeys) {
                        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                        if (MessageDigest.isEqual(providedBytes, keyBytes)) {
                            matches = true;
                            break;
                        }
                    }
                }

                if (matches) {
                    // Valid key — set authenticated principal in SecurityContext
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            "api-client",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    try {
                        filterChain.doFilter(request, response);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                } else {
                    log.warn("[Security] Rejected request — missing or invalid X-API-Key from {}",
                             request.getRemoteAddr());
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                        "{\"error\":\"Unauthorized\",\"message\":\"A valid X-API-Key header is required\"}"
                    );
                }
            }

            @Override
            protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
                // Skip this filter for public paths and WebSocket handshakes
                // (WebSocket authentication is enforced on STOMP CONNECT frames via WebSocketConfig)
                String path = request.getServletPath();
                return path.equals("/api/v1/health")
                    || path.startsWith("/actuator/")
                    || path.startsWith("/ws")
                    || path.startsWith("/ws-native");
            }
        };
    }
}
