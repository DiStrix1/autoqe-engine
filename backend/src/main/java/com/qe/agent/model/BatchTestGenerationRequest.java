package com.qe.agent.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Payload for the POST /api/v1/generate-tests/batch endpoint (#B-08).
 * Accepts a list of individual class test generation requests.
 */
public record BatchTestGenerationRequest(
        @NotEmpty(message = "requests list must not be empty")
        List<@Valid TestGenerationRequest> requests
) {}
