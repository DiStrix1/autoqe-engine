package com.qe.agent.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WebSocketConfig security interceptor.
 * Verifies STOMP CONNECT frame authentication against API keys.
 */
class WebSocketConfigTest {

    private WebSocketConfig webSocketConfig;
    private ChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        webSocketConfig = new WebSocketConfig();
        channel = mock(MessageChannel.class);
    }

    private ChannelInterceptor extractInterceptor(boolean securityEnabled, String rawKeys) {
        ReflectionTestUtils.setField(webSocketConfig, "securityEnabled", securityEnabled);
        ReflectionTestUtils.setField(webSocketConfig, "rawApiKeys", rawKeys);

        org.springframework.messaging.simp.config.ChannelRegistration registration =
                mock(org.springframework.messaging.simp.config.ChannelRegistration.class);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);

        webSocketConfig.configureClientInboundChannel(registration);
        verify(registration).interceptors(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("When security is disabled, CONNECT frame passes without API key")
    void securityDisabled_connectAllowedWithoutKey() {
        interceptor = extractInterceptor(false, "");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);
        assertNotNull(result);
    }

    @Test
    @DisplayName("When security is enabled, CONNECT frame without API key is rejected")
    void securityEnabled_missingKey_rejected() {
        interceptor = extractInterceptor(true, "secret-key-123");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(message, channel));
    }

    @Test
    @DisplayName("When security is enabled, CONNECT frame with valid X-API-Key header is accepted")
    void securityEnabled_validApiKeyHeader_accepted() {
        interceptor = extractInterceptor(true, "secret-key-123,other-key");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("X-API-Key", "secret-key-123");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);
        assertNotNull(result);

        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertNotNull(resultAccessor);
        assertNotNull(resultAccessor.getUser());
        assertEquals("api-client", resultAccessor.getUser().getName());
    }

    @Test
    @DisplayName("When security is enabled, CONNECT frame with valid passcode is accepted")
    void securityEnabled_validPasscode_accepted() {
        interceptor = extractInterceptor(true, "secret-key-123");

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setPasscode("secret-key-123");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);
        assertNotNull(result);

        StompHeaderAccessor resultAccessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertNotNull(resultAccessor);
        assertNotNull(resultAccessor.getUser());
    }
}
