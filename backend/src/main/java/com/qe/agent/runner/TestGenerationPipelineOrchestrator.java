package com.qe.agent.runner;

import com.qe.agent.agents.GeneratorAgent;
import com.qe.agent.agents.PlannerAgent;
import com.qe.agent.agents.RetrievalAgent;
import com.qe.agent.config.PathValidator;
import com.qe.agent.config.PathValidator.PathValidationException;
import com.qe.agent.metrics.QeMetrics;
import com.qe.agent.model.CancellationToken;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.model.TestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Canonical 5-stage test generation pipeline orchestrator.
 *
 * <p>Single source of truth for executing the entire multi-agent workflow:
 * <ol>
 *   <li><b>Stage 1 (Read):</b> Validate path via {@link PathValidator}, then load file.</li>
 *   <li><b>Stage 2 (Planner):</b> Produce targeted unit test strategy using {@link PlannerAgent}.</li>
 *   <li><b>Stage 3 (Retrieval):</b> Query Neo4j call graph and pgvector embeddings with {@link RetrievalAgent}.</li>
 *   <li><b>Stage 4 (Generator):</b> Synthesize complete JUnit 5 test class with {@link GeneratorAgent}.</li>
 *   <li><b>Stage 5 (Execution &amp; Self-Healing):</b> Execute tests in sandbox with {@link TestRunnerService}.</li>
 *   <li><b>Metrics:</b> Record execution results in {@link QeMetrics}.</li>
 * </ol>
 *
 * <p>Path security validation is fully delegated to {@link PathValidator}.
 */
@Slf4j
@Service
public class TestGenerationPipelineOrchestrator {

    private final PlannerAgent plannerAgent;
    private final RetrievalAgent retrievalAgent;
    private final GeneratorAgent generatorAgent;
    private final TestRunnerService testRunnerService;
    private final QeMetrics qeMetrics;
    private final PathValidator pathValidator;

    @Autowired
    public TestGenerationPipelineOrchestrator(
            PlannerAgent plannerAgent,
            RetrievalAgent retrievalAgent,
            GeneratorAgent generatorAgent,
            TestRunnerService testRunnerService,
            QeMetrics qeMetrics,
            PathValidator pathValidator) {
        this.plannerAgent = plannerAgent;
        this.retrievalAgent = retrievalAgent;
        this.generatorAgent = generatorAgent;
        this.testRunnerService = testRunnerService;
        this.qeMetrics = qeMetrics;
        this.pathValidator = pathValidator;
    }

    /**
     * Executes the complete end-to-end test generation and self-healing pipeline.
     *
     * @param request the test generation request
     * @return populated {@link TestGenerationResponse} reflecting final outcome
     */
    public TestGenerationResponse execute(TestGenerationRequest request) {
        return execute(request, null, null);
    }

    /**
     * Executes the pipeline with a live progress listener.
     */
    public TestGenerationResponse execute(TestGenerationRequest request, PipelineProgressListener listener) {
        return execute(request, listener, null);
    }

    /**
     * Executes the pipeline with an optional progress listener and cancellation token.
     *
     * @param listener          progress listener (may be null)
     * @param cancellationToken optional mid-flight cancellation token (may be null)
     */
    public TestGenerationResponse execute(
            TestGenerationRequest request,
            PipelineProgressListener listener,
            CancellationToken cancellationToken) {
        String strategy = request.strategy();
        String assertionLib = request.assertionLibrary();
        String directives = resolveDirectives(request.directives(), assertionLib);

        log.info("========== [Pipeline Start] targetClass={} targetFile={} strategy='{}' assertionLib='{}'",
                request.targetClassName(), request.targetFilePath(), strategy, assertionLib);
        if (listener != null) {
            listener.onProgress("INITIALIZING", "Starting pipeline for " + request.targetClassName());
        }

        // ── Stage 1: Validate path and read target file ────────────────────────
        Path resolvedPath;
        try {
            resolvedPath = pathValidator.validate(request.targetFilePath());
        } catch (PathValidationException e) {
            log.error("Stage 1 [Path Validation] REJECTED: {}", e.getMessage());
            if (listener != null) listener.onProgress("VALIDATION_ERROR", e.getMessage());
            return errorResponse(request.targetClassName(),
                    TestStatus.valueOf(e.getStatusCode()), e.getMessage());
        }

        String fileContent;
        try {
            if (!Files.exists(resolvedPath)) {
                log.error("Stage 1 [File Read] File does not exist: {}", resolvedPath);
                if (listener != null) listener.onProgress("FILE_NOT_FOUND", "Target file does not exist: " + resolvedPath);
                return errorResponse(request.targetClassName(), TestStatus.FILE_NOT_FOUND,
                        "Target file does not exist: " + resolvedPath);
            }
            fileContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            log.info("Stage 1 [File Read]: loaded {} chars from '{}'.", fileContent.length(), resolvedPath);
            if (listener != null) listener.onProgress("FILE_READ", "Loaded target source (" + fileContent.length() + " chars)");
        } catch (IOException e) {
            log.error("Stage 1 [File Read] FAILED: {}", e.getMessage(), e);
            if (listener != null) listener.onProgress("FILE_ERROR", "Cannot read target file: " + e.getMessage());
            return errorResponse(request.targetClassName(), TestStatus.FILE_NOT_FOUND,
                    "Cannot read target file: " + e.getMessage());
        }

        // ── Stage 2: Planner Phase ────────────────────────────────────────────
        String testPlan;
        try {
            log.info("Stage 2 [Planner]: invoking PlannerAgent with strategy='{}' and directives='{}'...",
                    strategy, directives);
            if (listener != null) listener.onProgress("PLANNING", "PlannerAgent formulating unit test strategy (" + strategy + ")...");
            testPlan = plannerAgent.planTestingStrategy(
                    request.targetClassName(),
                    request.targetFilePath(),
                    fileContent,
                    strategy,
                    directives);
            log.info("Stage 2 [Planner]: plan generated ({} chars).", testPlan.length());
            log.debug("Test Plan:\n{}", testPlan);
            if (listener != null) listener.onProgress("PLANNING", "Test plan successfully generated (" + testPlan.length() + " chars)");
        } catch (Exception e) {
            log.error("Stage 2 [Planner] FAILED (non-fatal, using fallback): {}", e.getMessage(), e);
            testPlan = String.format(
                    "PlannerAgent unavailable (%s). Proceeding with generic test generation.",
                    e.getMessage());
            if (listener != null) listener.onProgress("PLANNING_WARNING", "PlannerAgent fallback: " + e.getMessage());
        }

        // ── Stage 3: Retrieval Phase ──────────────────────────────────────────
        String codeContext;
        try {
            log.info("Stage 3 [Retrieval]: invoking RetrievalAgent with graph + vector tools...");
            if (listener != null) listener.onProgress("RETRIEVAL", "RetrievalAgent querying Neo4j graph and pgvector store...");
            codeContext = retrievalAgent.gatherCodeContext(
                    request.targetClassName(), testPlan, fileContent);
            log.info("Stage 3 [Retrieval]: context gathered ({} chars).", codeContext.length());
            log.debug("Code Context:\n{}", codeContext);
            if (listener != null) listener.onProgress("RETRIEVAL", "Context gathered (" + codeContext.length() + " chars)");
        } catch (Exception e) {
            log.error("Stage 3 [Retrieval] FAILED (non-fatal, using file content as fallback): {}",
                    e.getMessage(), e);
            codeContext = "RetrievalAgent unavailable: " + e.getMessage()
                    + "\n\nFallback - source file content:\n" + fileContent;
            if (listener != null) listener.onProgress("RETRIEVAL_WARNING", "RetrievalAgent fallback: " + e.getMessage());
        }

        // ── Stage 4: Generation Phase ─────────────────────────────────────────
        String rawGeneratedCode;
        try {
            log.info("Stage 4 [Generator]: invoking GeneratorAgent with strategy='{}' and directives='{}'...",
                    strategy, directives);
            if (listener != null) listener.onProgress("GENERATION", "GeneratorAgent synthesizing JUnit 5 test class...");
            String fullContext = "=== TEST PLAN (MUST BE IMPLEMENTED) ===\n" + testPlan
                    + "\n\n=== CODE CONTEXT ===\n" + codeContext
                    + "\n\n=== SOURCE FILE ===\n" + fileContent;
            rawGeneratedCode = generatorAgent.generateTests(
                    request.targetClassName(),
                    fullContext,
                    strategy,
                    directives);
            log.info("Stage 4 [Generator]: test code generated ({} chars).", rawGeneratedCode.length());
            if (listener != null) listener.onProgress("GENERATION", "Test code synthesized (" + rawGeneratedCode.length() + " chars)");
        } catch (Exception e) {
            log.error("Stage 4 [Generator] FAILED: {}", e.getMessage(), e);
            if (listener != null) listener.onProgress("GENERATION_ERROR", "GeneratorAgent failed: " + e.getMessage());
            TestGenerationResponse err = errorResponse(request.targetClassName(), TestStatus.GENERATION_FAILED,
                    "GeneratorAgent failed: " + e.getMessage());
            qeMetrics.recordGeneration(err.getStatus(), 0);
            return err;
        }

        // ── Stage 5: Self-Healing Execution ───────────────────────────────────
        log.info("Stage 5 [Runner]: starting self-healing execution (max {} retries).",
                TestRunnerService.MAX_RETRY_ATTEMPTS);
        if (listener != null) listener.onProgress("TEST_EXECUTION", "Starting self-healing execution in sandbox...");

        String currentTestCode = JavaSourceSanitizer.extractCodeBlock(rawGeneratedCode);

        // ── Dry-Run Short-Circuit (#B-09) ────────────────────────────────────
        if (Boolean.TRUE.equals(request.dryRun())) {
            log.info("Stage 5 [Dry-Run]: dryRun=true requested for {} — skipping disk write and sandbox execution.",
                    request.targetClassName());
            if (listener != null) {
                listener.onProgress("DRY_RUN_COMPLETE", "Dry-run complete. Test synthesized without disk writes or execution.");
            }
            TestGenerationResponse dryRunResp = buildResponse(
                    request.targetClassName(),
                    TestStatus.DRY_RUN,
                    0,
                    true,
                    currentTestCode,
                    "Dry-run mode: synthesized test code generated successfully without executing sandbox runner.",
                    null);
            qeMetrics.recordGeneration(dryRunResp.getStatus(), 0);
            return dryRunResp;
        }

        AtomicReference<Path> writtenTestFile = new AtomicReference<>(null);

        // Determine Maven project root
        Path repoRoot;
        try {
            repoRoot = testRunnerService.findMavenRoot(resolvedPath);
        } catch (IllegalArgumentException e) {
            log.error("Cannot locate Maven root from path '{}': {}", resolvedPath, e.getMessage());
            if (listener != null) listener.onProgress("ERROR", "Cannot locate Maven project root: " + e.getMessage());
            TestGenerationResponse err = errorResponse(request.targetClassName(), TestStatus.ERROR,
                    "Cannot locate Maven project root: " + e.getMessage());
            qeMetrics.recordGeneration(err.getStatus(), 0);
            return err;
        }

        // ── Step 1: Pre-compile check ──────────────────────────────────────────
        log.info("Stage 5 [Runner]: Pre-compile check on target project at '{}'.", repoRoot);
        if (listener != null) listener.onProgress("PRE_COMPILE", "Running pre-compile check on " + repoRoot);
        try {
            TestRunnerService.ProcessResult preCompile = testRunnerService.runPrecompile(repoRoot, listener);
            if (preCompile.exitCode() != 0) {
                log.error("Pre-compile check FAILED (exit {}).", preCompile.exitCode());
                if (listener != null) listener.onProgress("PRE_COMPILE_FAILED", "Target source failed mvn test-compile.");
                TestGenerationResponse err = failureResponse(
                        request.targetClassName(),
                        TestStatus.PRE_COMPILE_FAILED,
                        0,
                        currentTestCode,
                        "Target source failed mvn test-compile:\n" + preCompile.output());
                qeMetrics.recordGeneration(err.getStatus(), 0);
                return err;
            }
        } catch (TimeoutException e) {
            log.error("Pre-compile check timed out: {}", e.getMessage());
            if (listener != null) listener.onProgress("TIMEOUT", e.getMessage());
            TestGenerationResponse err = errorResponse(request.targetClassName(), TestStatus.TIMEOUT,
                    "Pre-compile timed out: " + e.getMessage());
            qeMetrics.recordGeneration(err.getStatus(), 0);
            return err;
        } catch (Exception e) {
            log.error("Pre-compile check threw exception: {}", e.getMessage(), e);
            if (listener != null) listener.onProgress("PRE_COMPILE_ERROR", e.getMessage());
            TestGenerationResponse err = errorResponse(request.targetClassName(), TestStatus.PRE_COMPILE_ERROR,
                    "Pre-compile error: " + e.getMessage());
            qeMetrics.recordGeneration(err.getStatus(), 0);
            return err;
        }
        log.info("Pre-compile check PASSED.");
        if (listener != null) listener.onProgress("PRE_COMPILE", "Pre-compile check PASSED.");

        // ── Cancellation check after pre-compile ──────────────────────────────
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            log.info("Pipeline cancelled after pre-compile.");
            if (listener != null) listener.onProgress("CANCELLED", "Generation cancelled by user.");
            TestGenerationResponse resp = failureResponse(
                    request.targetClassName(),
                    TestStatus.CANCELLED,
                    0,
                    currentTestCode,
                    "Generation cancelled by user before test execution.");
            qeMetrics.recordGeneration(resp.getStatus(), 0);
            return resp;
        }

        // ── Steps 2-6: Self-healing execution loop ────────────────────────────
        TestGenerationResponse response = null;
        for (int attempt = 1; attempt <= TestRunnerService.MAX_RETRY_ATTEMPTS; attempt++) {
            log.info("Execution attempt {}/{}.", attempt, TestRunnerService.MAX_RETRY_ATTEMPTS);
            if (listener != null) listener.onProgress("TEST_EXECUTION", "Starting test attempt " + attempt + "/" + TestRunnerService.MAX_RETRY_ATTEMPTS);

            // Step 2: Write test file
            String testClassName;
            try {
                currentTestCode = testRunnerService.writeTestFile(currentTestCode, repoRoot, fileContent);
                testClassName = JavaSourceSanitizer.extractPublicClassName(currentTestCode);
                String subPath = JavaSourceSanitizer.extractPackageName(currentTestCode).replace('.', '/');
                Path testDir = repoRoot.resolve("src").resolve("test").resolve("java");
                if (!subPath.isEmpty()) testDir = testDir.resolve(subPath);
                writtenTestFile.set(testDir.resolve(testClassName + ".java"));
                log.info("Test file written: {}.java", testClassName);
                if (listener != null) listener.onProgress("TEST_EXECUTION", "Test file written: " + testClassName + ".java");
            } catch (Exception e) {
                log.error("Failed to write test file: {}", e.getMessage(), e);
                if (listener != null) listener.onProgress("FILE_WRITE_ERROR", e.getMessage());
                response = failureResponse(request.targetClassName(), TestStatus.FILE_WRITE_ERROR,
                        attempt, currentTestCode, "Error writing test file: " + e.getMessage());
                break;
            }

            // Step 3: Execute test
            TestRunnerService.ProcessResult testResult;
            try {
                testResult = testRunnerService.runTest(repoRoot, testClassName, listener);
            } catch (TimeoutException e) {
                log.error("Maven test execution timed out on attempt {}: {}", attempt, e.getMessage());
                testRunnerService.cleanupTestFile(writtenTestFile.get());
                if (listener != null) listener.onProgress("TIMEOUT", e.getMessage());
                response = failureResponse(request.targetClassName(), TestStatus.TIMEOUT,
                        attempt, currentTestCode, "Maven execution timed out: " + e.getMessage());
                break;
            } catch (Exception e) {
                log.error("Maven test execution threw exception: {}", e.getMessage(), e);
                testRunnerService.cleanupTestFile(writtenTestFile.get());
                if (listener != null) listener.onProgress("EXECUTION_ERROR", e.getMessage());
                response = failureResponse(request.targetClassName(), TestStatus.EXECUTION_ERROR,
                        attempt, currentTestCode, "Error executing Maven test: " + e.getMessage());
                break;
            }

            // Step 4: Evaluate exit code
            if (testResult.exitCode() == 0) {
                log.info("Test PASSED on attempt {}.", attempt);
                if (listener != null) listener.onProgress("PASSED", "Test PASSED on attempt " + attempt);
                Integer coverage = testRunnerService.extractJaCoCoCoverage(repoRoot, request.targetClassName());
                response = buildResponse(request.targetClassName(), TestStatus.PASSED,
                        attempt, true, currentTestCode, testResult.output(), coverage);
                break;
            }

            log.warn("Test FAILED on attempt {} (exit {}). Log:\n{}",
                    attempt, testResult.exitCode(), testResult.output());

            if (attempt == TestRunnerService.MAX_RETRY_ATTEMPTS) {
                log.error("All {} attempts exhausted. Returning FAILED_AFTER_HEALING.", TestRunnerService.MAX_RETRY_ATTEMPTS);
                testRunnerService.cleanupTestFile(writtenTestFile.get());
                if (listener != null) listener.onProgress("FAILED_AFTER_HEALING", "All self-healing attempts exhausted.");
                response = failureResponse(request.targetClassName(), TestStatus.FAILED_AFTER_HEALING,
                        attempt, currentTestCode, testResult.output());
                break;
            }

            // Step 5: Check cancellation before repair
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("Pipeline cancelled before self-healing repair on attempt {}.", attempt);
                testRunnerService.cleanupTestFile(writtenTestFile.get());
                if (listener != null) listener.onProgress("CANCELLED", "Generation cancelled by user.");
                response = failureResponse(request.targetClassName(), TestStatus.CANCELLED,
                        attempt, currentTestCode, testResult.output() + "\n\n[Cancelled by user before self-healing repair.]");
                break;
            }

            // Trigger repair
            log.info("Triggering LLM self-healing repair (attempt {} of {})...", attempt, TestRunnerService.MAX_RETRY_ATTEMPTS - 1);
            if (listener != null) listener.onProgress("SELF_HEALING", "Triggering LLM self-healing repair (attempt " + attempt + " of " + (TestRunnerService.MAX_RETRY_ATTEMPTS - 1) + ")...");
            try {
                String repaired = generatorAgent.repairTests(
                        fileContent,
                        currentTestCode,
                        testResult.output()
                );
                currentTestCode = JavaSourceSanitizer.extractCodeBlock(repaired);
                log.info("Repaired test code received from GeneratorAgent.");
                if (listener != null) listener.onProgress("SELF_HEALING", "Repaired test code generated. Retrying execution...");
            } catch (Exception e) {
                log.error("GeneratorAgent.repairTests failed: {}", e.getMessage(), e);
                testRunnerService.cleanupTestFile(writtenTestFile.get());
                if (listener != null) listener.onProgress("SELF_HEALING_ERROR", "Self-healing error: " + e.getMessage());
                response = failureResponse(request.targetClassName(), TestStatus.FAILED_AFTER_HEALING,
                        attempt, currentTestCode, testResult.output() + "\n\n[Self-healing agent error]: " + e.getMessage());
                break;
            }
        }

        if (response == null) {
            response = failureResponse(request.targetClassName(), TestStatus.FAILED_AFTER_HEALING,
                    TestRunnerService.MAX_RETRY_ATTEMPTS, currentTestCode, "Max retries exhausted.");
        }

        log.info("========== [Pipeline End] status={} attempts={}",
                response.getStatus(), response.getAttempts());
        if (listener != null) {
            listener.onProgress("COMPLETED", "Pipeline finished: status=" + response.getStatus() + ", attempts=" + response.getAttempts());
        }

        qeMetrics.recordGeneration(response.getStatus(), response.getAttempts());

        return response;
    }

    // -------------------------------------------------------------------------
    // Response Factory Helpers
    // -------------------------------------------------------------------------

    private TestGenerationResponse buildResponse(
            String targetClassName,
            TestStatus status,
            int attempts,
            boolean success,
            String testCode,
            String logs,
            Integer coverage) {
        return TestGenerationResponse.builder()
                .targetClass(targetClassName)
                .status(status)
                .attempts(attempts)
                .compilationSuccessful(success)
                .generatedTestCode(testCode)
                .executionLogs(logs)
                .coverage(coverage)
                .build();
    }

    private TestGenerationResponse failureResponse(
            String targetClassName,
            TestStatus status,
            int attempts,
            String testCode,
            String logs) {
        return buildResponse(targetClassName, status, attempts, false, testCode, logs, null);
    }

    private TestGenerationResponse errorResponse(String targetClassName, TestStatus status, String logs) {
        return failureResponse(targetClassName, status, 0, null, logs);
    }

    private String resolveDirectives(String rawDirectives, String assertionLib) {
        String base = (rawDirectives != null && !rawDirectives.isBlank())
                ? rawDirectives : "None provided. Generate complete standard JUnit 5 assertions.";
        if ("ASSERTJ".equals(assertionLib)) {
            return base + "\n[ASSERTION STYLE]: Use AssertJ fluent assertions (import static org.assertj.core.api.Assertions.assertThat;). Also include import static org.junit.jupiter.api.Assertions.*;";
        } else if ("HAMCREST".equals(assertionLib)) {
            return base + "\n[ASSERTION STYLE]: Use Hamcrest matchers (import static org.hamcrest.MatcherAssert.assertThat; import static org.hamcrest.Matchers.*;). Also include import static org.junit.jupiter.api.Assertions.*;";
        }
        return base;
    }
}
