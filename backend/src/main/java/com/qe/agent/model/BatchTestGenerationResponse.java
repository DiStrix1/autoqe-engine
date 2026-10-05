package com.qe.agent.model;

import java.util.List;

/**
 * Aggregated response for the POST /api/v1/generate-tests/batch endpoint (#B-08).
 * Summarizes the outcome across all requested classes.
 */
public record BatchTestGenerationResponse(
        int total,
        int passed,
        int failed,
        long elapsedMillis,
        List<TestGenerationResponse> results
) {}
