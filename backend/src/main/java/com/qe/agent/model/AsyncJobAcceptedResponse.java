package com.qe.agent.model;

/**
 * Response payload returned when an asynchronous test generation job is accepted.
 */
public record AsyncJobAcceptedResponse(String jobId) {}
