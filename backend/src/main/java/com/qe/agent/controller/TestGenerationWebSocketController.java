package com.qe.agent.controller;

import com.qe.agent.model.CancellationToken;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.runner.PipelineProgressListener;
import com.qe.agent.runner.TestGenerationPipelineOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * TestGenerationWebSocketController - handles STOMP messages for real-time
 * bidirectional test generation pipeline streaming.
 *
 * <p>Improvement #14: replaces one-directional SSE with a full-duplex WebSocket
 * channel. SSE endpoints are retained as a permanent fallback.
 *
 * <p>Message flow:
 * <ul>
 *   <li>Client sends {@code /app/generate} with a {@link WebSocketGenerateRequest}.</li>
 *   <li>Backend publishes progress events to {@code /topic/pipeline/{sessionId}}.</li>
 *   <li>Client sends {@code /app/cancel} with {@code {"sessionId":"..."}} to stop mid-flight.</li>
 * </ul>
 */
@Slf4j
@Controller
public class TestGenerationWebSocketController {

    private final TestGenerationPipelineOrchestrator pipelineOrchestrator;
    private final SimpMessagingTemplate messagingTemplate;
    private final Executor taskExecutor;

    public record ActiveSession(
            CancellationToken token,
            String connectionId,
            String principalName) {}

    /** Active cancellation tokens keyed by sessionId. */
    private final ConcurrentHashMap<String, ActiveSession> activeSessions =
            new ConcurrentHashMap<>();

    public TestGenerationWebSocketController(
            TestGenerationPipelineOrchestrator pipelineOrchestrator,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("qeTaskExecutor") Executor taskExecutor) {
        this.pipelineOrchestrator = Objects.requireNonNull(pipelineOrchestrator);
        this.messagingTemplate = Objects.requireNonNull(messagingTemplate);
        this.taskExecutor = Objects.requireNonNull(taskExecutor);
    }

    // -------------------------------------------------------------------------
    // WebSocket message records
    // -------------------------------------------------------------------------

    public record WebSocketGenerateRequest(
            String sessionId,
            String targetClassName,
            String targetFilePath,
            String strategy,
            String directives) {}

    // -------------------------------------------------------------------------
    // Message handlers
    // -------------------------------------------------------------------------

    /**
     * Handles a test generation start request.
     * Publishes {@code progress} and {@code complete} events to
     * {@code /topic/pipeline/{sessionId}}.
     */
    @MessageMapping("/generate")
    public void handleGenerate(@Payload WebSocketGenerateRequest wsRequest,
                               org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = wsRequest.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[WS] Received generate request with no sessionId — ignoring.");
            return;
        }

        String connectionId = headerAccessor.getSessionId();
        java.security.Principal principal = headerAccessor.getUser();
        String principalName = principal != null ? principal.getName() : null;

        log.info("[WS sessionId={}] Starting pipeline for class={} (connectionId={}, principal={})",
                sessionId, wsRequest.targetClassName(), connectionId, principalName);

        CancellationToken cancellationToken = new CancellationToken();
        activeSessions.put(sessionId, new ActiveSession(cancellationToken, connectionId, principalName));

        String topic = "/topic/pipeline/" + sessionId;

        CompletableFuture.runAsync(() -> {

            try {
                TestGenerationRequest req = new TestGenerationRequest(
                        wsRequest.targetClassName(),
                        wsRequest.targetFilePath(),
                        wsRequest.strategy(),
                        wsRequest.directives()
                );

                PipelineProgressListener listener = (stage, message) -> {
                    Map<String, Object> event = Map.of(
                            "type", "progress",
                            "stage", stage,
                            "message", message,
                            "timestamp", System.currentTimeMillis()
                    );
                    sendEvent(topic, event);
                };

                TestGenerationResponse response = pipelineOrchestrator.execute(
                        req, listener, cancellationToken);

                Map<String, Object> doneEvent = Map.of(
                        "type", "complete",
                        "payload", response,
                        "timestamp", System.currentTimeMillis()
                );
                sendEvent(topic, doneEvent);
                log.info("[WS sessionId={}] Pipeline complete: status={}", sessionId, response.getStatus());

            } catch (Exception e) {
                log.error("[WS sessionId={}] Pipeline error: {}", sessionId, e.getMessage(), e);
                Map<String, Object> errEvent = Map.of(
                        "type", "error",
                        "message", e.getMessage() != null ? e.getMessage() : "Unknown error",
                        "timestamp", System.currentTimeMillis()
                );
                sendEvent(topic, errEvent);
            } finally {
                activeSessions.remove(sessionId);
            }
        }, taskExecutor);
    }

    /**
     * Handles a cancellation request.
     * Signals the running pipeline to stop at its next safe checkpoint,
     * verifying that the requesting client owns the target session.
     */
    @MessageMapping("/cancel")
    public void handleCancel(@Payload Map<String, String> body,
                             org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor) {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[WS] Received cancel with no sessionId.");
            return;
        }
        ActiveSession session = activeSessions.get(sessionId);
        if (session != null) {
            String callerConn = headerAccessor.getSessionId();
            java.security.Principal callerPrincipal = headerAccessor.getUser();
            String callerPrincipalName = callerPrincipal != null ? callerPrincipal.getName() : null;

            // Enforce session ownership check to prevent cross-session cancellation (IDOR)
            boolean authorized = (session.connectionId() != null && session.connectionId().equals(callerConn))
                    || (session.principalName() != null && session.principalName().equals(callerPrincipalName));

            if (!authorized) {
                log.warn("[Security] Unauthorized cancel attempt for sessionId={} by callerConn={}, callerPrincipal={}",
                        sessionId, callerConn, callerPrincipalName);
                return;
            }

            session.token().cancel();
            log.info("[WS sessionId={}] Cancellation token signalled by authorized caller.", sessionId);
            Map<String, Object> cancelEvent = Map.of(
                    "type", "cancelled",
                    "message", "Generation cancelled by user.",
                    "timestamp", System.currentTimeMillis()
            );
            sendEvent("/topic/pipeline/" + sessionId, cancelEvent);
        } else {
            log.warn("[WS sessionId={}] Cancel request received but no active session found.", sessionId);
        }
    }

    private void sendEvent(String destination, Object payload) {
        if (destination != null && payload != null) {
            messagingTemplate.convertAndSend(destination, payload);
        }
    }
}
