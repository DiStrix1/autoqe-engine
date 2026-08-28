package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * McpToolConfig — INTENTIONAL PLACEHOLDER for future Model Context Protocol (MCP)
 * tool registration.
 *
 * <p><strong>Audit note:</strong> This class is intentionally minimal. The current pipeline
 * registers tools directly via {@link OllamaConfig} using LangChain4j's
 * {@code AiServices.builder().tools(...)} API.
 *
 * <p>Improvement #K — Conditional MCP activation:
 * This class is reserved for a future migration to protocol-level MCP tool discovery,
 * where tools are registered dynamically without recompiling agent interfaces.
 *
 * <p>To activate real MCP tool discovery, set {@code qe.mcp.enabled=true} in
 * {@code application.properties}. When enabled, the future implementation will use:
 * <pre>
 *   McpToolProvider provider = McpToolProvider.builder()
 *       .mcpClients(mcpClient)
 *       .build();
 * </pre>
 *
 * <p>The {@code langchain4j-mcp} dependency is already declared in {@code pom.xml}
 * (PROJECT_SPECIFICATION.md Section 4.2 requirement).
 *
 * <p>Current activation status is logged at startup so operators can confirm
 * whether static or dynamic tool registration is in effect.
 *
 * <p>DO NOT remove this class — it is a deliberate architectural reservation.
 */
@Slf4j
@Configuration
public class McpToolConfig {

    /**
     * Set {@code qe.mcp.enabled=true} in application.properties to opt in to
     * protocol-level MCP tool discovery once the McpToolProvider API is wired.
     * Default is {@code false} — current static tool registration via OllamaConfig.
     */
    @Value("${qe.mcp.enabled:false}")
    private boolean mcpEnabled;

    public McpToolConfig() {
        // Note: @Value fields are not yet populated in the constructor.
        // The readiness log is emitted in the init method below.
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (mcpEnabled) {
            log.info("McpToolConfig: qe.mcp.enabled=true — MCP tool provider activation is pending implementation.");
        } else {
            log.debug("McpToolConfig: qe.mcp.enabled=false — using static OllamaConfig tool registration.");
        }
    }
}
