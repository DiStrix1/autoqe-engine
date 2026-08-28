package com.qe.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CorsConfig — allows the static frontend (served via file:// or any local origin)
 * to call the Spring Boot API endpoints without browser CORS rejections.
 *
 * <p>Permits all origins in development. Tighten {@code setAllowedOriginPatterns}
 * to specific domains before deploying to production.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Allow file:// origin and any localhost port (frontend dev servers)
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowCredentials(false);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
