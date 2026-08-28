package com.qe.agent.controller;

import com.qe.agent.model.AsyncJobAcceptedResponse;
import com.qe.agent.model.ErrorResponse;
import com.qe.agent.model.FileContentResponse;
import com.qe.agent.model.HealthStatusResponse;
import com.qe.agent.model.TestGenerationJob;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.model.TestStatus;
import com.qe.agent.runner.AsyncTestGenerationService;
import com.qe.agent.runner.TestGenerationPipelineOrchestrator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * TestGenerationController - REST entry point for the test generation pipeline.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/generate-tests} — Synchronous test generation pipeline.</li>
 *   <li>{@code POST /api/v1/generate-tests/async} — Asynchronous background submission.</li>
 *   <li>{@code GET /api/v1/generate-tests/status/{jobId}} — Async job status snapshot.</li>
 *   <li>{@code GET /api/v1/health} — Liveness and status probe.</li>
 *   <li>{@code GET /api/v1/file-content} — Source code reader for the workbench UI.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class TestGenerationController {

    private final TestGenerationPipelineOrchestrator pipelineOrchestrator;
    private final AsyncTestGenerationService asyncTestGenerationService;

    public TestGenerationController(
            TestGenerationPipelineOrchestrator pipelineOrchestrator,
            AsyncTestGenerationService asyncTestGenerationService) {
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.asyncTestGenerationService = asyncTestGenerationService;
    }

    /**
     * POST /api/v1/generate-tests
     *
     * <p>Executes the synchronous 5-stage pipeline and returns the result.
     */
    @PostMapping("/generate-tests")
    public ResponseEntity<TestGenerationResponse> generateTests(
            @Valid @RequestBody TestGenerationRequest request) {

        TestGenerationResponse response = pipelineOrchestrator.execute(request);

        if (TestStatus.INVALID_PATH.getValue().equals(response.getStatus())
                || TestStatus.FILE_NOT_FOUND.getValue().equals(response.getStatus())) {
            return ResponseEntity.badRequest().body(response);
        }
        if (TestStatus.GENERATION_FAILED.getValue().equals(response.getStatus())) {
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/generate-tests/async
     *
     * <p>Submits a test generation job to the background thread pool and returns a jobId.
     */
    @PostMapping("/generate-tests/async")
    public ResponseEntity<?> generateTestsAsync(
            @Valid @RequestBody TestGenerationRequest request) {

        try {
            if (request.targetFilePath() == null || request.targetFilePath().isBlank()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("targetFilePath must not be empty"));
            }
            Path rp = Path.of(request.targetFilePath()).toAbsolutePath().normalize();
            if (rp.toString().contains("..") || !rp.toString().endsWith(".java")) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Invalid or non-.java targetFilePath"));
            }
        } catch (InvalidPathException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid file path: " + e.getMessage()));
        }

        String jobId = asyncTestGenerationService.submitJob(request);
        log.info("Async job submitted: jobId={} targetClass={}", jobId, request.targetClassName());
        return ResponseEntity.accepted().body(new AsyncJobAcceptedResponse(jobId));
    }

    /**
     * GET /api/v1/generate-tests/status/{jobId}
     *
     * <p>Returns the current status snapshot and result for an async job.
     */
    @GetMapping("/generate-tests/status/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        TestGenerationJob job = asyncTestGenerationService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Job not found: " + jobId));
        }
        return ResponseEntity.ok(job);
    }

    /**
     * GET /api/v1/health
     *
     * <p>Liveness and status probe for health monitoring.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthStatusResponse> health() {
        return ResponseEntity.ok(new HealthStatusResponse(
                "UP",
                "backend-orchestrator",
                8080,
                System.currentTimeMillis()
        ));
    }

    /**
     * GET /api/v1/file-content
     *
     * <p>Safely reads and returns the source code of a specified .java file for the workbench code viewer.
     */
    @GetMapping("/file-content")
    public ResponseEntity<?> getFileContent(@RequestParam("path") String filePath) {
        try {
            if (filePath == null || filePath.isBlank()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("path parameter must not be empty"));
            }
            Path resolvedPath = Path.of(filePath).toAbsolutePath().normalize();
            String normalized = resolvedPath.toString();
            if (normalized.contains("..") || !normalized.endsWith(".java")) {
                return ResponseEntity.badRequest().body(new ErrorResponse("Target file must be a .java file"));
            }
            if (!Files.exists(resolvedPath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("File not found: " + filePath));
            }
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            return ResponseEntity.ok(new FileContentResponse(normalized, content));
        } catch (IOException e) {
            log.error("Failed to read file-content for {}: {}", filePath, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse("Cannot read file: " + e.getMessage()));
        }
    }
}
