package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WorkspacePathTranslator — translates container-internal Linux paths emitted by the
 * Python parser service (running in Docker) into real host-filesystem paths that
 * Spring Boot (running on Windows) can resolve with {@code Files.exists()} /
 * {@code Files.readString()}.
 *
 * <h2>Why this is needed</h2>
 * <p>The parser service mounts the project root at {@code /workspace} inside Docker and
 * writes that prefix into Neo4j / pgvector when storing {@code file_path} metadata.
 * Spring Boot runs directly on the Windows host and cannot resolve {@code /workspace/...}
 * paths.  This bean performs a simple prefix swap configured via
 * {@code application.properties}:
 *
 * <pre>
 * qe.workspace.container-prefix = /workspace
 * qe.workspace.host-root         = D:/Projects/qe-rag-system
 * </pre>
 *
 * <p>Example:
 * <pre>
 *   in  : /workspace/test-sandbox/src/main/java/com/qe/demo/Main.java
 *   out : D:/Projects/qe-rag-system/test-sandbox/src/main/java/com/qe/demo/Main.java
 * </pre>
 *
 * <p>If the path does not start with the configured container prefix it is returned
 * unchanged, so native Windows paths and direct invocations are unaffected.
 */
@Slf4j
@Component
public class WorkspacePathTranslator {

    private final String containerPrefix;
    private final String hostRoot;

    public WorkspacePathTranslator(
            @Value("${qe.workspace.container-prefix:/workspace}") String containerPrefix,
            @Value("${qe.workspace.host-root:}") String hostRoot) {
        // Normalise: strip trailing slashes so concat is predictable
        this.containerPrefix = containerPrefix.endsWith("/")
                ? containerPrefix.substring(0, containerPrefix.length() - 1)
                : containerPrefix;

        if (hostRoot != null && !hostRoot.isBlank()) {
            String clean = hostRoot.endsWith("/") || hostRoot.endsWith("\\")
                    ? hostRoot.substring(0, hostRoot.length() - 1)
                    : hostRoot;
            this.hostRoot = clean.replace('\\', '/');
        } else {
            // Code judo: Auto-derive workspace root from current runtime directory
            java.nio.file.Path current = java.nio.file.Paths.get("").toAbsolutePath().normalize();
            if (current.getFileName() != null && "backend".equalsIgnoreCase(current.getFileName().toString())) {
                java.nio.file.Path parent = current.getParent();
                if (parent != null) current = parent;
            }
            this.hostRoot = current.toString().replace('\\', '/');
        }
        log.info("WorkspacePathTranslator initialized: containerPrefix='{}', hostRoot='{}'",
                this.containerPrefix, this.hostRoot);
    }

    public String getHostRoot() {
        return this.hostRoot;
    }

    public String getContainerPrefix() {
        return this.containerPrefix;
    }

    /**
     * Translates a path that may use the container-internal prefix to the
     * equivalent host filesystem path.
     *
     * @param path the raw path string as received from the frontend or the database
     * @return the translated path ready for {@code Path.of()} on the host OS,
     *         or the original path unchanged if no translation applies
     */
    public String translate(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        // 1. Only translate paths that actually start with the container prefix
        if (!containerPrefix.isBlank() && !hostRoot.isBlank()
                && path.startsWith(containerPrefix)) {
            String remainder = path.substring(containerPrefix.length());
            if (remainder.contains("..") || remainder.contains("\0")) {
                log.warn("[Security] Path traversal attempt in translated path: {}", path);
                throw new IllegalArgumentException("Path traversal attempt rejected: " + path);
            }
            java.nio.file.Path hostRootPath = java.nio.file.Path.of(hostRoot).toAbsolutePath().normalize();
            String cleanRemainder = remainder.startsWith("/") || remainder.startsWith("\\")
                    ? remainder.substring(1)
                    : remainder;
            java.nio.file.Path resolved = hostRootPath.resolve(cleanRemainder).normalize();
            if (!resolved.startsWith(hostRootPath)) {
                log.warn("[Security] Path traversal escaping hostRoot rejected: {}", path);
                throw new IllegalArgumentException("Path resolves outside allowed host root: " + path);
            }
            String translated = resolved.toString().replace('\\', '/');
            log.debug("WorkspacePathTranslator: '{}' → '{}'", path, translated);
            return translated;
        }

        // 2. Relative path resolution against hostRoot
        if (!hostRoot.isBlank()) {
            if (path.contains("..") || path.contains("\0")) {
                log.warn("[Security] Path traversal attempt in relative path: {}", path);
                throw new IllegalArgumentException("Path traversal attempt rejected: " + path);
            }
            java.nio.file.Path rawPath = java.nio.file.Path.of(path);
            if (!rawPath.isAbsolute() && !path.startsWith("/") && !path.startsWith("\\")) {
                java.nio.file.Path hostRootPath = java.nio.file.Path.of(hostRoot).toAbsolutePath().normalize();
                java.nio.file.Path resolved = hostRootPath.resolve(path).normalize();
                if (resolved.startsWith(hostRootPath)) {
                    String translated = resolved.toString().replace('\\', '/');
                    log.debug("WorkspacePathTranslator relative match: '{}' → '{}'", path, translated);
                    return translated;
                }
            }
        }

        return path;
    }
}
