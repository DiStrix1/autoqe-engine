package com.qe.agent.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RateLimitInterceptor — per-IP sliding-window rate limiter for the test-generation endpoints.
 *
 * <p>Uses Bucket4j token-bucket algorithm. Each client IP gets its own bucket.
 * When the bucket is exhausted the request is rejected with HTTP 429.
 *
 * <p>Configuration (application.properties):
 * <pre>
 * qe.ratelimit.capacity=5          # max burst tokens
 * qe.ratelimit.refill-tokens=5     # tokens added per refill window
 * qe.ratelimit.refill-minutes=1    # refill window in minutes
 * </pre>
 *
 * <p>Defaults: 5 requests per minute per IP.
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Value("${qe.ratelimit.capacity:5}")
    private long capacity;

    @Value("${qe.ratelimit.refill-tokens:5}")
    private long refillTokens;

    @Value("${qe.ratelimit.refill-minutes:1}")
    private long refillMinutes;

    @Value("${qe.ratelimit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    private static final int MAX_TRACKED_IPS = 10_000;

    /**
     * Bounded access-order cache for per-IP rate-limiting buckets.
     * When size exceeds MAX_TRACKED_IPS, the least-recently-accessed entry is evicted,
     * preventing memory leaks without wiping buckets for active users.
     */
    private final Map<String, Bucket> buckets = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_TRACKED_IPS;
                }
            }
    );

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {

        String path = request.getServletPath();
        // Only rate-limit the expensive generation endpoints
        if (!path.startsWith("/api/v1/generate-tests")) {
            return true;
        }

        String clientIp = resolveClientIp(request);
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(clientIp, this::createBucket);
        }

        if (bucket.tryConsume(1)) {
            return true;
        }

        log.warn("[RateLimit] Request rejected for IP={} path={}", clientIp, path);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"error\":\"Too Many Requests\"," +
            "\"message\":\"Rate limit exceeded. Maximum " + refillTokens +
            " generation requests per " + refillMinutes + " minute(s) per IP.\"}"
        );
        return false;
    }

    private Bucket createBucket(String ip) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofMinutes(refillMinutes))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Resolves the real client IP.
     * Only honors {@code X-Forwarded-For} when {@code qe.ratelimit.trust-forwarded-for=true}
     * (behind a verified reverse proxy that strips client-supplied headers).
     * Defaults to {@code request.getRemoteAddr()} to prevent spoofing.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String ip = null;
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                ip = forwarded.split(",")[0].trim();
            }
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        // Sanitize: allow only standard IPv4/IPv6/hostname characters
        ip = ip.replaceAll("[^a-zA-Z0-9.:%_-]", "");
        if (ip.length() > 45) {
            ip = ip.substring(0, 45);
        }
        return ip.isBlank() ? "unknown" : ip;
    }
}
