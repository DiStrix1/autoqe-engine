package com.qe.agent.runner;

import com.qe.agent.agents.GeneratorAgent;
import com.qe.agent.agents.PlannerAgent;
import com.qe.agent.agents.RetrievalAgent;
import com.qe.agent.metrics.QeMetrics;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.model.TestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Canonical 5-stage test generation pipeline orchestrator.
 *
 * <p>Single source of truth for executing the entire multi-agent workflow:
 * <ol>
 *   <li><b>Stage 0 & 1 (Validation & Read):</b> Normalize path, verify .java extension, load file.</li>
 *   <li><b>Stage 2 (Planner):</b> Produce targeted unit test strategy using {@link PlannerAgent}.</li>
 *   <li><b>Stage 3 (Retrieval):</b> Query Neo4j call graph and pgvector embeddings with {@link RetrievalAgent}.</li>
 *   <li><b>Stage 4 (Generator):</b> Synthesize complete JUnit 5 test class with {@link GeneratorAgent}.</li>
 *   <li><b>Stage 5 (Execution & Self-Healing):</b> Execute tests in sandbox with {@link TestRunnerService}.</li>
 *   <li><b>Metrics:</b> Record execution results in {@link QeMetrics}.</li>
 * </ol>
 */
@Slf4j
@Service
public class TestGenerationPipelineOrchestrator {

    private final PlannerAgent plannerAgent;
    private final RetrievalAgent retrievalAgent;
    private final GeneratorAgent generatorAgent;
    private final TestRunnerService testRunnerService;
    private final QeMetrics qeMetrics;

    public TestGenerationPipelineOrchestrator(
            PlannerAgent plannerAgent,
            RetrievalAgent retrievalAgent,
            GeneratorAgent generatorAgent,
            TestRunnerService testRunnerService,
            QeMetrics qeMetrics) {
        this.plannerAgent = plannerAgent;
        this.retrievalAgent = retrievalAgent;
        this.generatorAgent = generatorAgent;
        this.testRunnerService = testRunnerService;
        this.qeMetrics = qeMetrics;
    }

    /**
     * Executes the complete end-to-end test generation and self-healing pipeline.
     *
     * @param request the test generation request
     * @return populated {@link TestGenerationResponse} reflecting final outcome
     */
    public TestGenerationResponse execute(TestGenerationRequest request) {
        String strategy = (request.strategy() != null && !request.strategy().isBlank())
                ? request.strategy() : "Standard JUnit 5";
        String directives = (request.directives() != null && !request.directives().isBlank())
                ? request.directives() : "None provided. Generate complete standard JUnit 5 assertions.";

        log.info("========== [Pipeline Start] targetClass={} targetFile={} strategy='{}' directives='{}'",
                request.targetClassName(), request.targetFilePath(), strategy, directives);

        // ── Stage 0: Security Path Validation ─────────────────────────────────
        Path resolvedPath;
        try {
            if (request.targetFilePath() == null || request.targetFilePath().isBlank()) {
                return errorResponse(request.targetClassName(), TestStatus.INVALID_PATH, "targetFilePath must not be empty");
            }
            resolvedPath = Path.of(request.targetFilePath()).toAbsolutePath().normalize();
            String normalized = resolvedPath.toString();
            if (normalized.contains("..")) {
                log.error("Stage 0 [Path Validation] REJECTED: path traversal attempt: {}", request.targetFilePath());
                return errorResponse(request.targetClassName(), TestStatus.INVALID_PATH,
                        "Path traversal attempt rejected: " + request.targetFilePath());
            }
            if (!normalized.endsWith(".java")) {
                log.error("Stage 0 [Path Validation] REJECTED: not a .java file: {}", normalized);
                return errorResponse(request.targetClassName(), TestStatus.INVALID_PATH,
                        "Target file must be a .java file: " + normalized);
            }
        } catch (InvalidPathException e) {
            log.error("Stage 0 [Path Validation] Invalid path: {}", e.getMessage());
            return errorResponse(request.targetClassName(), TestStatus.INVALID_PATH, "Invalid file path: " + e.getMessage());
        }

        // ── Stage 1: Read Target File from Disk ────────────────────────────────
        String fileContent;
        try {
            if (!Files.exists(resolvedPath)) {
                log.error("Stage 1 [File Read] File does not exist: {}", resolvedPath);
                return errorResponse(request.targetClassName(), TestStatus.FILE_NOT_FOUND,
                        "Target file does not exist: " + resolvedPath);
            }
            fileContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            log.info("Stage 1 [File Read]: loaded {} chars from '{}'.", fileContent.length(), resolvedPath);
        } catch (IOException e) {
            log.error("Stage 1 [File Read] FAILED: {}", e.getMessage(), e);
            return errorResponse(request.targetClassName(), TestStatus.FILE_NOT_FOUND,
                    "Cannot read target file: " + e.getMessage());
        }

        // ── Stage 2: Planner Phase ────────────────────────────────────────────
        String testPlan;
        try {
            log.info("Stage 2 [Planner]: invoking PlannerAgent with strategy='{}' and directives='{}'...",
                    strategy, directives);
            testPlan = plannerAgent.planTestingStrategy(
                    request.targetClassName(),
                    request.targetFilePath(),
                    fileContent,
                    strategy,
                    directives);
            log.info("Stage 2 [Planner]: plan generated ({} chars).", testPlan.length());
            log.debug("Test Plan:\n{}", testPlan);
        } catch (Exception e) {
            log.error("Stage 2 [Planner] FAILED (non-fatal, using fallback): {}", e.getMessage(), e);
            testPlan = String.format(
                    "PlannerAgent unavailable (%s). Proceeding with generic test generation.",
                    e.getMessage());
        }

        // ── Stage 3: Retrieval Phase ──────────────────────────────────────────
        String codeContext;
        try {
            log.info("Stage 3 [Retrieval]: invoking RetrievalAgent with graph + vector tools...");
            codeContext = retrievalAgent.gatherCodeContext(
                    request.targetClassName(), testPlan, fileContent);
            log.info("Stage 3 [Retrieval]: context gathered ({} chars).", codeContext.length());
            log.debug("Code Context:\n{}", codeContext);
        } catch (Exception e) {
            log.error("Stage 3 [Retrieval] FAILED (non-fatal, using file content as fallback): {}",
                    e.getMessage(), e);
            codeContext = "RetrievalAgent unavailable: " + e.getMessage()
                    + "\n\nFallback - source file content:\n" + fileContent;
        }

        // ── Stage 4: Generation Phase ─────────────────────────────────────────
        String rawGeneratedCode;
        try {
            log.info("Stage 4 [Generator]: invoking GeneratorAgent with strategy='{}' and directives='{}'...",
                    strategy, directives);
            String fullContext = "=== CODE CONTEXT ===\n" + codeContext
                    + "\n\n=== SOURCE FILE ===\n" + fileContent;
            rawGeneratedCode = generatorAgent.generateTests(
                    request.targetClassName(),
                    fullContext,
                    strategy,
                    directives);
            log.info("Stage 4 [Generator]: test code generated ({} chars).", rawGeneratedCode.length());
        } catch (Exception e) {
            log.error("Stage 4 [Generator] FAILED: {}", e.getMessage(), e);
            TestGenerationResponse err = errorResponse(request.targetClassName(), TestStatus.GENERATION_FAILED,
                    "GeneratorAgent failed: " + e.getMessage());
            qeMetrics.recordGeneration(err.getStatus(), 0);
            return err;
        }

        // ── Stage 5: Self-Healing Execution ───────────────────────────────────
        log.info("Stage 5 [Runner]: starting self-healing execution (max {} retries).",
                TestRunnerService.MAX_RETRY_ATTEMPTS);
        TestGenerationResponse response = testRunnerService.executeWithSelfHealing(
                request.targetClassName(),
                request.targetFilePath(),
                fileContent,
                rawGeneratedCode,
                codeContext);

        log.info("========== [Pipeline End] status={} attempts={}",
                response.getStatus(), response.getAttempts());

        // Record outcome metrics
        qeMetrics.recordGeneration(response.getStatus(), response.getAttempts());

        return response;
    }

    private TestGenerationResponse errorResponse(String targetClassName, TestStatus status, String logs) {
        return TestGenerationResponse.builder()
                .targetClass(targetClassName)
                .status(status)
                .attempts(0)
                .compilationSuccessful(false)
                .executionLogs(logs)
                .build();
    }
}
