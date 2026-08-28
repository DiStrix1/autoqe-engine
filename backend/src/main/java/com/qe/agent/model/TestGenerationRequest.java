package com.qe.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for the POST /api/v1/generate-tests endpoint.
 *
 * <p>CONTRACT (PROJECT_SPECIFICATION.md Section 4.2):
 * <pre>
 * {
 *   "targetClassName": "UserService",
 *   "targetFilePath": "/path/to/UserService.java",
 *   "directives": "Focus on boundary conditions & edge-case exceptions",
 *   "strategy": "Edge-Case Stress"
 * }
 * </pre>
 *
 * <p>Improvement #E: @NotBlank constraints ensure the controller rejects missing or
 * blank fields with HTTP 400 before any file I/O or path validation is attempted.
 * Prevents NullPointerException on null targetFilePath.
 */
public record TestGenerationRequest(
        @NotBlank(message = "targetClassName must not be blank")
        String targetClassName,

        @NotBlank(message = "targetFilePath must not be blank")
        String targetFilePath,

        String directives,

        String strategy
) {
    public TestGenerationRequest(String targetClassName, String targetFilePath) {
        this(targetClassName, targetFilePath, null, "Standard JUnit 5");
    }
}
