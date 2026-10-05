package com.qe.agent.controller;

import com.qe.agent.model.CancellationToken;
import com.qe.agent.runner.TestGenerationPipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TestGenerationWebSocketController.
 * Verifies cancellation authorization, preventing cross-session cancellation (IDOR).
 */
class TestGenerationWebSocketControllerTest {

    private TestGenerationPipelineOrchestrator pipelineOrchestrator;
    private SimpMessagingTemplate messagingTemplate;
    private Executor taskExecutor;
    private TestGenerationWebSocketController controller;

    @BeforeEach
    void setUp() {
        pipelineOrchestrator = mock(TestGenerationPipelineOrchestrator.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        taskExecutor = mock(Executor.class);

        controller = new TestGenerationWebSocketController(
                pipelineOrchestrator,
                messagingTemplate,
                taskExecutor
        );
    }

    @Test
    @DisplayName("Authorized cancellation by session creator with matching connectionId cancels token")
    void handleCancel_authorizedConnectionId_cancelsToken() {
        CancellationToken token = new CancellationToken();
        assertFalse(token.isCancelled());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession> activeSessions =
                (ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession>)
                        ReflectionTestUtils.getField(controller, "activeSessions");
        assertNotNull(activeSessions);
        activeSessions.put("session-123", new TestGenerationWebSocketController.ActiveSession(token, "conn-abc", null));

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("conn-abc");
        when(headerAccessor.getUser()).thenReturn(null);

        controller.handleCancel(Map.of("sessionId", "session-123"), headerAccessor);

        assertTrue(token.isCancelled());
        verify(messagingTemplate).convertAndSend(eq("/topic/pipeline/session-123"), any(Map.class));
    }

    @Test
    @DisplayName("Unauthorized cancellation by different connectionId is rejected (IDOR defense)")
    void handleCancel_unauthorizedConnectionId_rejectsCancellation() {
        CancellationToken token = new CancellationToken();
        assertFalse(token.isCancelled());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession> activeSessions =
                (ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession>)
                        ReflectionTestUtils.getField(controller, "activeSessions");
        assertNotNull(activeSessions);
        activeSessions.put("session-victim", new TestGenerationWebSocketController.ActiveSession(token, "conn-victim", "alice"));

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("conn-attacker");
        Principal attackerPrincipal = mock(Principal.class);
        when(attackerPrincipal.getName()).thenReturn("mallory");
        when(headerAccessor.getUser()).thenReturn(attackerPrincipal);

        controller.handleCancel(Map.of("sessionId", "session-victim"), headerAccessor);

        assertFalse(token.isCancelled(), "Token must NOT be cancelled by an unauthorized caller");
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Map.class));
    }

    @Test
    @DisplayName("Authorized cancellation by matching principal succeeds even if connectionId differs")
    void handleCancel_authorizedPrincipal_cancelsToken() {
        CancellationToken token = new CancellationToken();
        assertFalse(token.isCancelled());

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession> activeSessions =
                (ConcurrentHashMap<String, TestGenerationWebSocketController.ActiveSession>)
                        ReflectionTestUtils.getField(controller, "activeSessions");
        assertNotNull(activeSessions);
        activeSessions.put("session-456", new TestGenerationWebSocketController.ActiveSession(token, "conn-original", "bob"));

        SimpMessageHeaderAccessor headerAccessor = mock(SimpMessageHeaderAccessor.class);
        when(headerAccessor.getSessionId()).thenReturn("conn-reconnected");
        Principal bobPrincipal = mock(Principal.class);
        when(bobPrincipal.getName()).thenReturn("bob");
        when(headerAccessor.getUser()).thenReturn(bobPrincipal);

        controller.handleCancel(Map.of("sessionId", "session-456"), headerAccessor);

        assertTrue(token.isCancelled());
        verify(messagingTemplate).convertAndSend(eq("/topic/pipeline/session-456"), any(Map.class));
    }
}
