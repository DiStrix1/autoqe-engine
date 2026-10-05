package com.qe.agent.config;

import org.springframework.lang.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig - configures STOMP over WebSocket for real-time bidirectional
 * streaming of pipeline progress events and mid-flight job cancellation.
 *
 * <p>Improvement #14: replaces one-directional SSE with a full-duplex channel.
 * SSE endpoints are kept as a permanent fallback.
 *
 * <p>Architecture:
 * <ul>
 *   <li>Clients connect to {@code ws://localhost:8080/ws} (SockJS fallback available).</li>
 *   <li>Clients SEND to {@code /app/generate} or {@code /app/cancel}.</li>
 *   <li>Backend BROADCASTS to {@code /topic/pipeline/{sessionId}}.</li>
 * </ul>
 */
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@SuppressWarnings("null")
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${qe.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:4173}")
    private String allowedOriginsRaw;

    @Value("${qe.security.enabled:false}")
    private boolean securityEnabled;

    @Value("${qe.security.api-keys:}")
    private String rawApiKeys;

    @Override
    public void configureMessageBroker(@NonNull MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(@NonNull StompEndpointRegistry registry) {
        List<String> origins = new ArrayList<>();
        if (allowedOriginsRaw != null && !allowedOriginsRaw.isBlank()) {
            Arrays.stream(allowedOriginsRaw.split(","))
                    .map(s -> s.trim())
                    .filter(s -> !s.isBlank())
                    .forEach(origins::add);
        }
        // Always include dev patterns if not already covered
        if (!origins.contains("http://localhost:*")) origins.add("http://localhost:*");
        if (!origins.contains("http://127.0.0.1:*")) origins.add("http://127.0.0.1:*");

        String[] patterns = origins.toArray(new String[0]);

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(patterns)
                .withSockJS();
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(patterns);
        log.info("WebSocket endpoints registered: /ws (SockJS), /ws-native (native) with patterns: {}", origins);
    }

    @Override
    public void configureClientInboundChannel(@NonNull ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    if (securityEnabled) {
                        Set<String> validKeys = Arrays.stream(rawApiKeys.split(","))
                                .map(String::trim)
                                .filter(k -> !k.isBlank())
                                .collect(Collectors.toSet());

                        String providedKey = accessor.getFirstNativeHeader(SecurityConfig.API_KEY_HEADER);
                        if (providedKey == null || providedKey.isBlank()) {
                            providedKey = accessor.getPasscode();
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

                        if (!matches) {
                            log.warn("[Security] Rejected WebSocket STOMP connection — missing or invalid X-API-Key");
                            throw new org.springframework.messaging.MessageDeliveryException(
                                    "Unauthorized: A valid X-API-Key header or passcode is required for WebSocket connections");
                        }

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                "api-client",
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
                        );
                        if (!accessor.isMutable()) {
                            accessor = StompHeaderAccessor.wrap(message);
                            accessor.setUser(authentication);
                            log.debug("[Security] Authenticated WebSocket client via STOMP CONNECT frame (wrapped)");
                            return org.springframework.messaging.support.MessageBuilder.createMessage(
                                    message.getPayload(), accessor.getMessageHeaders());
                        } else {
                            accessor.setUser(authentication);
                            log.debug("[Security] Authenticated WebSocket client via STOMP CONNECT frame");
                        }
                    }
                }
                return message;
            }
        });
    }
}

