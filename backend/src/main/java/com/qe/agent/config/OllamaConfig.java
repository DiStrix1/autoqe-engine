package com.qe.agent.config;

import com.qe.agent.agents.GeneratorAgent;
import com.qe.agent.agents.PlannerAgent;
import com.qe.agent.agents.RetrievalAgent;
import com.qe.agent.tools.GraphQueryTool;
import com.qe.agent.tools.VectorQueryTool;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;

/**
 * OllamaConfig - central Spring configuration for the AI model and all agent beans.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Creates the primary {@link ChatLanguageModel} bean with explicit
 *       {@code numCtx=8192} (PROJECT_SPECIFICATION.md Section 6.2 mandate).
 *       Marking it {@code @Primary} causes the LangChain4j Ollama spring-boot
 *       starter's {@code @ConditionalOnMissingBean} auto-configuration to back off,
 *       preventing a duplicate-bean conflict.</li>
 *   <li>Creates the {@link EmbeddingModel} bean using the quantized in-process
 *       MiniLM-L6-v2 ONNX model — same 384-dim architecture as the Python ingestion
 *       service, guaranteeing pgvector dimension compatibility.</li>
 *   <li>Wires {@link PlannerAgent}, {@link RetrievalAgent}, and
 *       {@link GeneratorAgent} via {@link AiServices} builder, injecting the
 *       graph/vector query tools into {@link RetrievalAgent}.</li>
 * </ol>
 */
@Slf4j
@Configuration
public class OllamaConfig {

    @Value("${langchain4j.ollama.chat-model.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${langchain4j.ollama.chat-model.model-name:llama3:8b}")
    private String modelName;

    @Value("${langchain4j.ollama.chat-model.timeout:120s}")
    private String timeoutRaw;

    /**
     * Improvement #I: temperature is now read from application.properties
     * (langchain4j.ollama.chat-model.temperature) instead of being hardcoded.
     * Default 0.0 preserves the original deterministic behaviour.
     */
    @Value("${langchain4j.ollama.chat-model.temperature:0.0}")
    private double temperature;

    /**
     * Improvement #N: expected embedding dimension read from config so a model
     * swap only requires updating this property (and the Python service's
     * EMBEDDING_DIM env var) — no code changes needed.
     */
    @Value("${qe.embedding.expected-dimensions:384}")
    private int expectedEmbeddingDimensions;

    // -------------------------------------------------------------------------
    // Foundation beans
    // -------------------------------------------------------------------------

    /**
     * Primary OllamaChatModel with numCtx=8192.
     *
     * <p>Defined as {@code @Primary} so that all AiServices builder calls and the
     * {@code AgentApplication} smoke-test consistently use this configured instance.
     * In LangChain4j 1.0.0 {@code ChatLanguageModel} was renamed to {@code ChatModel};
     * returning the concrete {@link OllamaChatModel} avoids the interface name entirely
     * and is accepted by {@code AiServices.builder().chatModel()}.
     */
    @Bean
    @Primary
    public OllamaChatModel chatLanguageModel() {
        long timeoutSeconds = parseTimeoutSeconds(timeoutRaw);
        log.info("Creating primary OllamaChatModel: url={}, model={}, numCtx=8192, timeout={}s, temperature={}",
                baseUrl, modelName, timeoutSeconds, temperature);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)          // Improvement #I: from property
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .numCtx(8192)
                .build();
    }

    /**
     * In-process MiniLM-L6-v2 quantized ONNX embedding model.
     *
     * <p>Produces 384-dimensional float vectors — identical dimensionality to the
     * Python {@code sentence-transformers/all-MiniLM-L6-v2} model used by the
     * ingestion service, ensuring cosine similarity queries in pgvector are valid.
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        AllMiniLmL6V2QuantizedEmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();
        int actualDim = model.dimension();
        if (actualDim != expectedEmbeddingDimensions) {
            throw new IllegalStateException(String.format(
                    "Embedding model dimension mismatch: model produces %d-dim vectors, "
                    + "but qe.embedding.expected-dimensions=%d. "
                    + "Update both this property and the Python service EMBEDDING_DIM env var.",
                    actualDim, expectedEmbeddingDimensions));
        }
        log.info("AllMiniLmL6V2QuantizedEmbeddingModel loaded (ONNX in-process, dim={} ✓).", actualDim);
        return model;
    }

    // -------------------------------------------------------------------------
    // Agent beans
    // -------------------------------------------------------------------------

    /**
     * PlannerAgent bean — no tools; pure LLM reasoning over provided file content.
     *
     * <p>In LangChain4j 1.0.0 the builder method was renamed from
     * {@code .chatLanguageModel()} to {@code .chatModel()}.
     */
    @Bean
    public PlannerAgent plannerAgent(OllamaChatModel chatModel) {
        log.debug("Wiring PlannerAgent via AiServices builder.");
        return AiServices.builder(PlannerAgent.class)
                .chatModel(chatModel)
                .build();
    }

    /**
     * RetrievalAgent bean — equipped with GraphQueryTool and VectorQueryTool
     * so the LLM can autonomously query Neo4j and pgvector during generation.
     */
    @Bean
    public RetrievalAgent retrievalAgent(OllamaChatModel chatModel,
                                         GraphQueryTool graphQueryTool,
                                         VectorQueryTool vectorQueryTool) {
        log.debug("Wiring RetrievalAgent with GraphQueryTool + VectorQueryTool.");
        return AiServices.builder(RetrievalAgent.class)
                .chatModel(chatModel)
                .tools(List.of(graphQueryTool, vectorQueryTool))
                .build();
    }

    /**
     * GeneratorAgent bean — no tools; writes and repairs JUnit 5 test code.
     */
    @Bean
    public GeneratorAgent generatorAgent(OllamaChatModel chatModel) {
        log.debug("Wiring GeneratorAgent via AiServices builder.");
        return AiServices.builder(GeneratorAgent.class)
                .chatModel(chatModel)
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long parseTimeoutSeconds(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return 120L;
            }
            if (raw.endsWith("s")) {
                return Long.parseLong(raw.substring(0, raw.length() - 1).trim());
            }
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("Cannot parse timeout '{}', defaulting to 120s.", raw);
            return 120L;
        }
    }
}
