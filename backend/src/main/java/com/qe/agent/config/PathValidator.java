package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * PathValidator — single canonical authority for all path security checks.
 *
 * <p>Consolidates the path-traversal, extension, and sandbox-root validation
 * that was previously duplicated across:
 * <ul>
 *   <li>{@code TestGenerationPipelineOrchestrator} Stage 0</li>
 *   <li>{@code TestGenerationController#generateTestsAsync}</li>
 *   <li>{@code TestGenerationController#getFileContent}</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 *   Path resolved = pathValidator.validate(rawFilePath); // throws on any violation
 * }</pre>
 */
@Slf4j
public final class PathValidator {

    private final WorkspacePathTranslator pathTranslator;
    private final String allowedRoot;

    public PathValidator(WorkspacePathTranslator pathTranslator, String allowedRoot) {
        this.pathTranslator = pathTranslator;
        this.allowedRoot = allowedRoot;
    }

    /**
     * Translates, normalizes, and fully validates a raw path string.
     *
     * @param rawPath the raw path as received from the client or database
     * @return the resolved, normalized {@link Path} ready for file I/O
     * @throws PathValidationException if the path is blank, contains traversal sequences,
     *                                  is not a {@code .java} file, or falls outside the sandbox root
     */
    public Path validate(String rawPath) throws PathValidationException {
        String translated = pathTranslator.translate(rawPath);

        if (translated == null || translated.isBlank()) {
            throw new PathValidationException("targetFilePath must not be empty", "INVALID_PATH");
        }
        if (translated.contains("..")) {
            log.error("[PathValidator] Path traversal attempt rejected: {}", translated);
            throw new PathValidationException("Path traversal attempt rejected: " + translated, "INVALID_PATH");
        }

        Path resolved;
        try {
            resolved = Path.of(translated).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.error("[PathValidator] Invalid path syntax: {}", e.getMessage());
            throw new PathValidationException("Invalid file path: " + e.getMessage(), "INVALID_PATH");
        }

        if (!resolved.toString().endsWith(".java")) {
            log.error("[PathValidator] Non-.java file rejected: {}", resolved);
            throw new PathValidationException("Target file must be a .java file: " + resolved, "INVALID_PATH");
        }

        String effectiveRoot = (allowedRoot != null && !allowedRoot.isBlank())
                ? allowedRoot
                : pathTranslator.getHostRoot();

        if (effectiveRoot != null && !effectiveRoot.isBlank()) {
            Path root = Path.of(effectiveRoot).toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                log.error("[PathValidator] Path {} is outside sandbox root {}", resolved, root);
                throw new PathValidationException(
                        "Target file must reside within allowed sandbox root: " + root, "INVALID_PATH");
            }
        }

        return resolved;
    }

    /**
     * Immutable exception carrying both a human-readable message and a
     * machine-readable status code for mapping to HTTP or pipeline responses.
     */
    public static final class PathValidationException extends Exception {
        private final String statusCode;

        public PathValidationException(String message, String statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public String getStatusCode() {
            return statusCode;
        }
    }
}
