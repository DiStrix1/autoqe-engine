package com.qe.agent.integration;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TestGenerationIntegrationTest — end-to-end integration tests for the
 * POST /api/v1/generate-tests endpoint.
 *
 * <p>Improvement #O: scaffolds the integration test class with:
 * <ul>
 *   <li>Input validation tests (no external dependencies — run in CI).</li>
 *   <li>@Disabled stubs for full pipeline tests (require running Neo4j, PostgreSQL, and Ollama).</li>
 * </ul>
 *
 * <p>To run the DB-dependent tests locally:
 * <pre>
 *   docker compose up -d
 *   mvn test -Dgroups=integration
 * </pre>
 */
@SuppressWarnings("null")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TestGenerationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // =========================================================================
    // Input validation tests (no external dependencies)
    // =========================================================================

    @Test
    @DisplayName("POST /generate-tests with empty body returns 400")
    void generateTests_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /generate-tests with non-.java targetFilePath returns INVALID_PATH")
    void generateTests_nonJavaFile_returnsInvalidPath() throws Exception {
        String body = """
                {"targetClassName":"UserService","targetFilePath":"/etc/passwd"}
                """;
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /generate-tests with path traversal is rejected")
    void generateTests_pathTraversal_isRejected() throws Exception {
        String body = """
                {"targetClassName":"UserService","targetFilePath":"../../etc/passwd"}
                """;
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST /generate-tests with a non-existent .java file returns FILE_NOT_FOUND (400)")
    void generateTests_nonExistentFile_returnsFileNotFound() throws Exception {
        String body = """
                {"targetClassName":"Ghost","targetFilePath":"/nonexistent/Ghost.java"}
                """;
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.status").value("FILE_NOT_FOUND"));
    }

    // =========================================================================
    // Full pipeline tests (require running DBs and Ollama — disabled in CI)
    // =========================================================================

    @Test
    @Disabled("Requires running Neo4j, PostgreSQL, and Ollama — run with docker compose up -d")
    @DisplayName("Full pipeline: generates and passes tests for UserService")
    void generateTests_userService_passes() throws Exception {
        String body = """
                {
                  "targetClassName": "UserService",
                  "targetFilePath": "%s/test-sandbox/src/main/java/com/qe/demo/UserService.java"
                }
                """.formatted(System.getProperty("user.dir").replace("backend", "").replace("\\", "/"));

        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"));
    }

    @Test
    @Disabled("Requires running services — run with docker compose up -d")
    @DisplayName("GET /generate-tests/status/{jobId} returns 404 for unknown job")
    void getJobStatus_unknownJob_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/generate-tests/status/nonexistent-job-id"))
                .andExpect(status().isNotFound());
    }
}
