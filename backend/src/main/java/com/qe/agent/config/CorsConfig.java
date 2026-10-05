package com.qe.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CorsConfig — restricts cross-origin access to explicitly configured origins.
 *
 * <p>Allowed origins are driven by the {@code qe.cors.allowed-origins} property
 * (comma-separated list). In local dev this defaults to the three standard frontend
 * ports. In production, set the property to your real domain(s) only.
 *
 * <p><b>Never</b> use {@code "*"} with credentials or in a networked deployment.
 */
@Configuration
public class CorsConfig {

    /**
     * Comma-separated list of allowed CORS origins.
     * Example production value: {@code https://autoqe.example.com}
     * Example local dev value:  {@code http://localhost:3000,http://localhost:5173}
     */
    @Value("${qe.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:4173}")
    private String allowedOriginsRaw;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(s -> s.trim())
                .filter(s -> !s.isBlank())
                .toList();

        config.setAllowedOrigins(origins);
        config.setAllowCredentials(false);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
