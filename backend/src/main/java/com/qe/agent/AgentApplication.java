package com.qe.agent;

import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * AgentApplication - Spring Boot entry point for the QE Agent Backend Orchestrator.
 *
 * <p>On startup this class registers a {@link CommandLineRunner} that performs a
 * smoke-test against the local Ollama instance using the primary {@link OllamaChatModel} bean.
 * A simple prompt is sent and the response is logged, confirming end-to-end LLM connectivity
 * before the REST API begins accepting real requests.
 */
@Slf4j
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    /**
     * Runs once immediately after the application context is fully started.
     *
     * <p>Uses the auto-configured {@link OllamaChatModel} bean to verify connectivity.
     *
     * @param chatModel primary OllamaChatModel managed bean
     * @return CommandLineRunner executed after startup
     */
    @Bean
    public CommandLineRunner ollamaConnectionSmokeTest(OllamaChatModel chatModel) {
        return args -> {
            log.info("=================================================================");
            log.info("  QE Agent Backend - Ollama Connectivity Smoke-Test");
            log.info("=================================================================");

            try {
                String prompt = "Reply with exactly one sentence confirming you are operational.";
                log.info("Sending smoke-test prompt to Ollama...");
                log.debug("Prompt: \"{}\"", prompt);

                String response = chatModel.chat(prompt);

                log.info("-----------------------------------------------------------------");
                log.info("  [OLLAMA RESPONSE] {}", response);
                log.info("-----------------------------------------------------------------");
                log.info("  Ollama connectivity smoke-test PASSED.");
                log.info("=================================================================");

            } catch (Exception ex) {
                log.error("=================================================================");
                log.error("  Ollama connectivity smoke-test FAILED: {}", ex.getMessage());
                log.error("  Hint: Ensure 'ollama serve' is running and the configured model is pulled.");
                log.error("=================================================================", ex);
                // Non-fatal: REST API continues to start so the service is still reachable.
            }
        };
    }
}
