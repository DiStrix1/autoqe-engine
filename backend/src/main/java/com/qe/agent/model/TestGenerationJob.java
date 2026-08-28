package com.qe.agent.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * TestGenerationJob — snapshot of an async test generation job.
 *
 * <p>Improvement #J: supports the async endpoint pair
 * (POST /generate-tests/async, GET /generate-tests/status/{jobId}).
 * Jobs are stored in a {@code ConcurrentHashMap} in
 * {@link com.qe.agent.runner.AsyncTestGenerationService}.
 */
@Data
@Builder
public class TestGenerationJob {

    /** Unique job identifier (UUID string). */
    private final String jobId;

    /**
     * Lifecycle status of the job itself (not the test outcome status).
     * Values: PENDING, RUNNING, DONE, ERROR.
     */
    private volatile String jobStatus;

    /** The final pipeline response — null until the job completes. */
    private volatile TestGenerationResponse response;

    /** ISO-8601 timestamp when the job was accepted. */
    private final Instant createdAt;
}
