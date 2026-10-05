package com.qe.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * VectorQueryTool - LangChain4j tool bean for semantic similarity queries
 * against the PostgreSQL + pgvector {@code code_embeddings} table.
 *
 * <p>Uses the same {@code all-MiniLM-L6-v2} embedding architecture as the
 * Python ingestion service (384-dimensional dense vectors), ensuring that
 * query embeddings are dimension-compatible with stored embeddings.
 *
 * <p>The {@link JdbcTemplate} and {@link EmbeddingModel} beans are injected
 * by Spring from {@code OllamaConfig} and Spring Boot's JDBC auto-configuration.
 *
 * <p>pgvector cosine distance operator: {@code <=>}
 * Similarity score: {@code 1 - cosine_distance} (higher = more similar).
 */
@Slf4j
@Component
public class VectorQueryTool {

    private static final Pattern VECTOR_LITERAL_PATTERN = Pattern.compile("^\\[[-?\\d.,eE\\s]+\\]$");

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * Improvement #N: expected embedding dimension is injected from config
     * (qe.embedding.expected-dimensions) instead of being hardcoded as 384.
     * When the embedding model is changed, only the property needs updating.
     */
    private final int expectedDimensions;

    public VectorQueryTool(JdbcTemplate jdbcTemplate,
                           EmbeddingModel embeddingModel,
                           @Value("${qe.embedding.expected-dimensions:384}") int expectedDimensions) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.expectedDimensions = expectedDimensions;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    @Tool("Find semantically similar Java methods in the PostgreSQL vector store using cosine similarity. "
            + "Provide a natural language description of the functionality you are looking for "
            + "(e.g. 'validate email address format' or 'save entity to database'). "
            + "Returns the top matching method bodies ranked by semantic similarity.")
    @SuppressWarnings("null")
    public String findSimilarMethods(
            @P("Natural language description of the method functionality to search for") String queryText,
            @P("Number of results to return, typically 3 to 5") int topK) {

        log.debug("[VectorQueryTool] findSimilarMethods('{}', topK={})", queryText, topK);

        // Guard: clamp topK to a safe maximum to prevent unbounded memory load
        // if the LLM passes an unreasonably large value.
        final int safeTopK = Math.min(Math.max(topK, 1), 10);
        if (safeTopK != topK) {
            log.warn("[VectorQueryTool] topK={} clamped to {}", topK, safeTopK);
        }

        try {
            // Embed the query text using the same MiniLM model as the Python ingestion side.
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();
            float[] vector = queryEmbedding.vector();

            // Guard: the embedding model must return the configured number of dimensions.
            // A dimension mismatch produces a cryptic pgvector SQL error instead of a
            // clear message, so we validate eagerly here.
            if (vector.length != expectedDimensions) {
                String msg = String.format(
                        "[VectorQueryTool] Embedding dimension mismatch: expected %d, got %d. "
                        + "Verify that AllMiniLmL6V2QuantizedEmbeddingModel is configured correctly.",
                        expectedDimensions, vector.length);
                log.error(msg);
                return "Error: " + msg;
            }

            String vectorLiteral = toVectorLiteral(vector);
            if (!VECTOR_LITERAL_PATTERN.matcher(vectorLiteral).matches()) {
                throw new IllegalArgumentException("Generated vector literal contains invalid characters");
            }

            // pgvector cosine distance: '<=>'.  We use string-interpolation for the vector
            // literal because the standard JDBC '?' placeholder does not support the
            // '?::vector' cast syntax reliably across all PostgreSQL JDBC driver versions.
            // The vector is model-generated and verified to contain only digits/commas/brackets.
            String sql = String.format("""
                    SELECT method_id, class_name, method_name, file_path, method_body,
                           1 - (embedding <=> '%s'::vector) AS similarity
                    FROM code_embeddings
                    ORDER BY embedding <=> '%s'::vector
                    LIMIT %d
                    """, vectorLiteral, vectorLiteral, safeTopK);

            List<String> rows = jdbcTemplate.query(sql, (rs, rowNum) -> String.format(
                    "[similarity=%.4f] %s.%s (id=%s)\n%s",
                    rs.getDouble("similarity"),
                    rs.getString("class_name"),
                    rs.getString("method_name"),
                    rs.getString("method_id"),
                    rs.getString("method_body")
            ));

            if (rows.isEmpty()) {
                return "No similar methods found for query: '" + queryText
                        + "'. Ensure the codebase has been ingested.";
            }
            return "Top " + rows.size() + " semantically similar methods:\n\n"
                    + String.join("\n---\n", rows);

        } catch (Exception ex) {
            log.error("[VectorQueryTool] findSimilarMethods failed: {}", ex.getMessage(), ex);
            return "Error during vector similarity search: " + ex.getMessage();
        }
    }

    @Tool("Retrieve the full stored record (class name, method name, file path, method body) "
            + "for a specific method from the PostgreSQL vector store by its method ID. "
            + "Provide the method ID in the format ClassName#methodName.")
    public String getMethodEmbeddingRecord(
            @P("Method ID in the format ClassName#methodName") String methodId) {

        log.debug("[VectorQueryTool] getMethodEmbeddingRecord({})", methodId);
        try {
            String sql = """
                    SELECT class_name, method_name, file_path, method_body
                    FROM code_embeddings
                    WHERE method_id = ?
                    """;

            List<String> rows = jdbcTemplate.query(sql, (rs, rowNum) -> String.format(
                    "Class: %s | Method: %s | File: %s\nBody:\n%s",
                    rs.getString("class_name"),
                    rs.getString("method_name"),
                    rs.getString("file_path"),
                    rs.getString("method_body")
            ), methodId);

            if (rows.isEmpty()) {
                return "No record found in vector store for method ID: " + methodId;
            }
            return rows.get(0);

        } catch (Exception ex) {
            log.error("[VectorQueryTool] getMethodEmbeddingRecord failed: {}", ex.getMessage(), ex);
            return "Error retrieving method record: " + ex.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Converts a float array to the pgvector literal format, e.g. {@code [0.1,0.2,...]}.
     *
     * @param vector embedding vector produced by {@link EmbeddingModel}
     * @return pgvector-compatible string representation
     */
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
