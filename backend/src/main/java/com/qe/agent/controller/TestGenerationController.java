package com.qe.agent.controller;

import com.qe.agent.config.PathValidator;
import com.qe.agent.config.PathValidator.PathValidationException;
import com.qe.agent.model.AsyncJobAcceptedResponse;
import com.qe.agent.model.BatchTestGenerationRequest;
import com.qe.agent.model.BatchTestGenerationResponse;
import com.qe.agent.model.ErrorResponse;
import com.qe.agent.model.FileContentResponse;
import com.qe.agent.model.HealthStatusResponse;
import com.qe.agent.model.TestGenerationJob;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.model.TestStatus;
import com.qe.agent.runner.AsyncTestGenerationService;
import com.qe.agent.runner.PipelineProgressListener;
import com.qe.agent.runner.TestGenerationPipelineOrchestrator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.qe.agent.model.CancellationToken;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;

/**
 * TestGenerationController - REST entry point for the test generation pipeline.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/generate-tests} — Synchronous test generation pipeline.</li>
 *   <li>{@code POST /api/v1/generate-tests/stream} — SSE streaming (JSON body).</li>
 *   <li>{@code POST /api/v1/generate-tests/async} — Asynchronous background submission.</li>
 *   <li>{@code GET /api/v1/generate-tests/status/{jobId}} — Async job status snapshot.</li>
 *   <li>{@code GET /api/v1/health} — Liveness and status probe.</li>
 *   <li>{@code GET /api/v1/file-content} — Source code reader for the workbench UI.</li>
 * </ul>
 *
 * <p>All path validation is delegated to {@link PathValidator}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@SuppressWarnings("null")
public class TestGenerationController {

    private final TestGenerationPipelineOrchestrator pipelineOrchestrator;
    private final AsyncTestGenerationService asyncTestGenerationService;
    private final PathValidator pathValidator;
    private final Executor taskExecutor;

    public TestGenerationController(
            TestGenerationPipelineOrchestrator pipelineOrchestrator,
            AsyncTestGenerationService asyncTestGenerationService,
            PathValidator pathValidator,
            @Qualifier("qeTaskExecutor") Executor taskExecutor) {
        this.pipelineOrchestrator = pipelineOrchestrator;
        this.asyncTestGenerationService = asyncTestGenerationService;
        this.pathValidator = pathValidator;
        this.taskExecutor = taskExecutor;
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
     * POST /api/v1/generate-tests/batch
     *
     * <p>Executes test generation sequentially for a list of target classes (#B-08).
     * Aggregates execution metrics and individual class results.
     */
    @PostMapping("/generate-tests/batch")
    public ResponseEntity<BatchTestGenerationResponse> generateTestsBatch(
            @Valid @RequestBody BatchTestGenerationRequest batchRequest) {

        long startTime = System.currentTimeMillis();
        List<TestGenerationResponse> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (TestGenerationRequest req : batchRequest.requests()) {
            try {
                TestGenerationResponse response = pipelineOrchestrator.execute(req);
                results.add(response);
                if (TestStatus.PASSED.getValue().equals(response.getStatus())
                        || TestStatus.DRY_RUN.getValue().equals(response.getStatus())) {
                    passed++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Batch test generation error for class {}: {}", req.targetClassName(), e.getMessage());
                TestGenerationResponse errResponse = TestGenerationResponse.builder()
                        .targetClass(req.targetClassName())
                        .status(TestStatus.ERROR)
                        .compilationSuccessful(false)
                        .executionLogs("Batch execution error: " + e.getMessage())
                        .build();
                results.add(errResponse);
                failed++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        BatchTestGenerationResponse batchResponse = new BatchTestGenerationResponse(
                results.size(),
                passed,
                failed,
                elapsed,
                results
        );

        return ResponseEntity.ok(batchResponse);
    }

    /**
     * POST /api/v1/generate-tests/stream
     *
     * <p>Server-Sent Events (SSE) streaming endpoint accepting JSON body.
     * This is the canonical streaming endpoint — use POST to send all generation options.
     */
    @PostMapping(value = "/generate-tests/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTestGenerationPost(@Valid @RequestBody TestGenerationRequest request) {
        return createSseStream(request);
    }

    private SseEmitter createSseStream(TestGenerationRequest req) {
        SseEmitter emitter = new SseEmitter(900_000L); // 15-minute timeout
        CancellationToken cancellationToken = new CancellationToken();

        emitter.onCompletion(() -> {
            log.debug("SSE emitter completed for class: {}", req.targetClassName());
            cancellationToken.cancel();
        });
        emitter.onTimeout(() -> {
            log.warn("SSE emitter timed out for class: {}", req.targetClassName());
            cancellationToken.cancel();
        });
        emitter.onError(e -> {
            log.debug("SSE emitter error for class {}: {}", req.targetClassName(), e.getMessage());
            cancellationToken.cancel();
        });

        CompletableFuture.runAsync(() -> {
            try {
                PipelineProgressListener listener = (stage, message) -> {
                    if (cancellationToken.isCancelled()) {
                        return;
                    }
                    try {
                        Map<String, Object> payload = Map.of(
                                "stage", stage,
                                "message", message,
                                "timestamp", System.currentTimeMillis()
                        );
                        emitter.send(SseEmitter.event()
                                .name("progress")
                                .data(payload, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        log.debug("SSE client disconnected: {}", e.getMessage());
                        cancellationToken.cancel();
                    }
                };

                TestGenerationResponse response = pipelineOrchestrator.execute(req, listener, cancellationToken);
                if (!cancellationToken.isCancelled()) {
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data(response, MediaType.APPLICATION_JSON));
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("SSE stream error: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"), MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }, taskExecutor);

        return emitter;
    }

    /**
     * POST /api/v1/generate-tests/async
     *
     * <p>Submits a test generation job to the background thread pool and returns a jobId.
     * Validates the path up-front using {@link PathValidator} before queuing.
     */
    @PostMapping("/generate-tests/async")
    public ResponseEntity<?> generateTestsAsync(
            @Valid @RequestBody TestGenerationRequest request) {

        try {
            pathValidator.validate(request.targetFilePath());
        } catch (PathValidationException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
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
     * Path validation is fully delegated to {@link PathValidator}.
     */
    @GetMapping("/file-content")
    public ResponseEntity<?> getFileContent(@RequestParam("path") String filePath) {
        Path resolvedPath;
        try {
            resolvedPath = pathValidator.validate(filePath);
        } catch (PathValidationException e) {
            log.warn("[Security] Path validation rejected for {}: {}", filePath, e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }

        try {
            if (!Files.exists(resolvedPath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("File not found: " + filePath));
            }
            String content = Files.readString(resolvedPath, StandardCharsets.UTF_8);
            return ResponseEntity.ok(new FileContentResponse(resolvedPath.toString(), content));
        } catch (IOException e) {
            log.error("Failed to read file-content for {}: {}", filePath, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("Cannot read file: " + e.getMessage()));
        }
    }
}
