package com.qe.agent.model;

/**
 * Response payload for the /api/v1/health liveness and readiness probe.
 */
public record HealthStatusResponse(
        String status,
        String service,
        int port,
        long timestamp
) {}
