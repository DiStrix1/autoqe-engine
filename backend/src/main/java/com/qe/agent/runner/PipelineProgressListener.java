package com.qe.agent.runner;

/**
 * Functional callback interface for streaming real-time stage transitions
 * and log messages from the test generation pipeline to connected consumers (e.g. SSE emitters).
 */
@FunctionalInterface
public interface PipelineProgressListener {

    /**
     * Called whenever a stage begins, updates, or logs an event.
     *
     * @param stage   the current pipeline stage (e.g., "PLANNING", "RETRIEVAL", "GENERATION", "TEST_EXECUTION", "SELF_HEALING", "COMPLETED", "FAILED")
     * @param message human-readable progress note or raw log line
     */
    void onProgress(String stage, String message);
}
