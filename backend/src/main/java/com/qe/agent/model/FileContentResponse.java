package com.qe.agent.model;

/**
 * Response payload returning Java file source content for the workbench code viewer.
 */
public record FileContentResponse(
        String path,
        String content
) {}
