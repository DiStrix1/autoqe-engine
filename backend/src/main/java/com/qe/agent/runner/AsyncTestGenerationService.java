package com.qe.agent.runner;

import com.qe.agent.model.TestGenerationJob;
import com.qe.agent.model.TestGenerationRequest;
import com.qe.agent.model.TestGenerationResponse;
import com.qe.agent.model.TestStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AsyncTestGenerationService — wraps the full test generation pipeline for
 * non-blocking execution via Spring's @Async thread pool.
 *
 * <p>Delegates execution to {@link TestGenerationPipelineOrchestrator} and
 * stores jobs in a bounded, TTL-evicted in-memory store.
 */
@Slf4j
@Service
public class AsyncTestGenerationService {

    private static final int MAX_CACHE_SIZE = 500;
    private static final Duration JOB_TTL = Duration.ofHours(2);

    private final TestGenerationPipelineOrchestrator pipelineOrchestrator;
    private final ConcurrentHashMap<String, TestGenerationJob> jobStore = new ConcurrentHashMap<>();

    public AsyncTestGenerationService(TestGenerationPipelineOrchestrator pipelineOrchestrator) {
        this.pipelineOrchestrator = pipelineOrchestrator;
    }

    /**
     * Submits a test generation job to the async thread pool.
     *
     * @param request the test generation request
     * @return jobId that can be polled with {@link #getJob(String)}
     */
    public String submitJob(TestGenerationRequest request) {
        evictStaleJobs();

        String jobId = UUID.randomUUID().toString();
        TestGenerationJob job = TestGenerationJob.builder()
                .jobId(jobId)
                .jobStatus("PENDING")
                .createdAt(Instant.now())
                .build();
        jobStore.put(jobId, job);
        runPipelineAsync(jobId, request);
        return jobId;
    }

    /**
     * Returns the current snapshot of the job, or {@code null} if not found.
     */
    public TestGenerationJob getJob(String jobId) {
        return jobStore.get(jobId);
    }

    // -------------------------------------------------------------------------
    // Async execution
    // -------------------------------------------------------------------------

    @Async("qeTaskExecutor")
    public void runPipelineAsync(String jobId, TestGenerationRequest request) {
        TestGenerationJob job = jobStore.get(jobId);
        if (job == null) {
            log.warn("[Async jobId={}] Job not found in store when starting execution.", jobId);
            return;
        }

        job.setJobStatus("RUNNING");
        log.info("[Async jobId={}] Starting pipeline for class={}", jobId, request.targetClassName());

        try {
            TestGenerationResponse response = pipelineOrchestrator.execute(request);
            job.setResponse(response);
            job.setJobStatus(TestStatus.PASSED.getValue().equals(response.getStatus()) ? "DONE" : "FAILED");
            log.info("[Async jobId={}] Pipeline complete: status={}", jobId, response.getStatus());
        } catch (Exception e) {
            log.error("[Async jobId={}] Uncaught pipeline error: {}", jobId, e.getMessage(), e);
            job.setResponse(TestGenerationResponse.builder()
                    .targetClass(request.targetClassName())
                    .status(TestStatus.ERROR)
                    .executionLogs("Async pipeline error: " + e.getMessage())
                    .build());
            job.setJobStatus("ERROR");
        }
    }

    private void evictStaleJobs() {
        Instant cutoff = Instant.now().minus(JOB_TTL);
        jobStore.entrySet().removeIf(entry -> {
            Instant created = entry.getValue().getCreatedAt();
            return created != null && created.isBefore(cutoff);
        });

        // Enforce hard cap if still exceeding limit
        if (jobStore.size() > MAX_CACHE_SIZE) {
            jobStore.keySet().stream().limit(jobStore.size() - MAX_CACHE_SIZE).forEach(jobStore::remove);
        }
    }
}
