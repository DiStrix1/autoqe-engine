package com.qe.agent.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
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

    @BeforeEach
    void setUp() {
        assertNotNull(mockMvc, "MockMvc should be injected by SpringBootTest");
    }

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
                {"targetClassName":"Ghost","targetFilePath":"/workspace/test-sandbox/Ghost.java"}
                """;
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.status").value("FILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /generate-tests with a path outside allowed sandbox root returns INVALID_PATH (400)")
    void generateTests_outsideSandboxRoot_returnsInvalidPath() throws Exception {
        String body = """
                {"targetClassName":"Ghost","targetFilePath":"/nonexistent/Ghost.java"}
                """;
        mockMvc.perform(post("/api/v1/generate-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.status").value("INVALID_PATH"));
    }

    @Test
    @DisplayName("POST /generate-tests/batch with empty requests list returns 400")
    void generateTestsBatch_emptyList_returns400() throws Exception {
        String body = """
                {"requests":[]}
                """;
        mockMvc.perform(post("/api/v1/generate-tests/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /generate-tests/batch with invalid paths aggregates results")
    void generateTestsBatch_invalidPaths_aggregatesResults() throws Exception {
        String body = """
                {
                  "requests": [
                    {"targetClassName":"Ghost1","targetFilePath":"/nonexistent/Ghost1.java"},
                    {"targetClassName":"Ghost2","targetFilePath":"/nonexistent/Ghost2.java"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/v1/generate-tests/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.passed").value(0))
                .andExpect(jsonPath("$.results").isArray());
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
