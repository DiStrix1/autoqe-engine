package com.qe.agent.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TestRunnerService — Maven sandbox test execution and coverage engine.
 *
 * <p>Single responsibility: executes {@code mvn test-compile} and {@code mvn test}
 * commands against target sandbox projects via {@link ProcessBuilder}, manages
 * generated test file disk lifecycles, and extracts JaCoCo coverage metrics.
 *
 * <p>Pipeline orchestration and LLM self-healing retry logic are canonically
 * handled by {@link TestGenerationPipelineOrchestrator}.
 */
@Slf4j
@Service
public class TestRunnerService {

    /**
     * Hard upper bound on self-healing retries (PROJECT_SPECIFICATION.md Section 5, Step 6).
     */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    private final int timeoutSeconds;

    @Autowired
    public TestRunnerService(@Value("${qe.runner.maven-timeout-seconds:120}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }


    // =========================================================================
    // Execution and File Lifecycle Helpers
    // =========================================================================

    /**
     * Walks up the directory tree from {@code start} to find the nearest
     * directory containing a {@code pom.xml}.
     *
     * @param start a file or directory inside the Maven project
     * @return path to the Maven project root
     * @throws IllegalArgumentException if no {@code pom.xml} is found
     */
    public Path findMavenRoot(Path start) {
        Path current = Files.isRegularFile(start) ? start.getParent() : start;
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException(
                "No pom.xml found in directory hierarchy starting from: " + start);
    }

    /**
     * Writes the generated test source to the correct Maven test source tree.
     *
     * <p>Derives the subdirectory from the {@code package} statement in the test code
     * (e.g. {@code package com.qe.demo;} → {@code src/test/java/com/qe/demo/}).
     * The class file is named after the {@code public class} declaration.
     *
     * @param testCode          complete Java source code of the test class
     * @param repoRoot          Maven project root
     * @param targetFileContent source code of the target class
     * @return sanitized and written test code
     * @throws IOException if the directory cannot be created or the file cannot be written
     */
    public String writeTestFile(String testCode, Path repoRoot, String targetFileContent) throws IOException {
        testCode = JavaSourceSanitizer.ensurePackage(testCode, targetFileContent);
        testCode = JavaSourceSanitizer.ensureStaticImports(testCode);
        JavaSourceSanitizer.validateSafety(testCode);
        String packageName = JavaSourceSanitizer.extractPackageName(testCode);
        String className   = JavaSourceSanitizer.extractPublicClassName(testCode);
        if ("GeneratedTest".equals(className) && !testCode.contains("class GeneratedTest")) {
            throw new IllegalArgumentException("Generated code does not contain a valid Java class declaration.");
        }
        String subPath     = packageName.isEmpty() ? "" : packageName.replace('.', '/');

        Path testDir = repoRoot.resolve("src").resolve("test").resolve("java");
        if (!subPath.isEmpty()) {
            testDir = testDir.resolve(subPath);
        }
        Files.createDirectories(testDir);

        Path testFile = testDir.resolve(className + ".java").toAbsolutePath().normalize();
        Path normalizedRepoRoot = repoRoot.toAbsolutePath().normalize();
        if (!testFile.startsWith(normalizedRepoRoot)) {
            throw new SecurityException("Target test file path outside repository root: " + testFile);
        }
        Files.writeString(testFile, testCode, StandardCharsets.UTF_8);
        log.debug("Wrote {} bytes to: {}", testCode.length(), testFile);
        return testCode;
    }

    /**
     * Deletes the generated test file from disk after a failed/aborted run.
     *
     * <p>Prevents stale broken test files from accumulating in the sandbox's
     * test directory across multiple generation attempts. Files are only kept
     * on disk when the test run {@code PASSED}.
     *
     * @param path path to the test file, or {@code null} if no file was written
     */
    public void cleanupTestFile(Path path) {
        if (path == null) return;
        try {
            if (Files.deleteIfExists(path)) {
                log.info("[Cleanup] Deleted failed test file: {}", path.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("[Cleanup] Could not delete test file '{}': {}", path, e.getMessage());
        }
    }

    /**
     * Executes {@code mvn test-compile -q} to verify whether the target project compiles.
     */
    public ProcessResult runPrecompile(Path repoRoot, PipelineProgressListener listener)
            throws IOException, InterruptedException, TimeoutException {
        // Automatically purge any stray GeneratedTest.java left behind from previous un-sanitized runs
        cleanupTestFile(repoRoot.resolve("src/test/java/com/qe/demo/GeneratedTest.java"));
        return runMaven(List.of("test-compile", "-q"), repoRoot, listener);
    }

    /**
     * Executes {@code mvn test -Dtest=<testClassName>} to run a specific test suite.
     */
    public ProcessResult runTest(Path repoRoot, String testClassName, PipelineProgressListener listener)
            throws IOException, InterruptedException, TimeoutException {
        if (!JavaSourceSanitizer.isValidJavaIdentifier(testClassName)) {
            throw new IllegalArgumentException("Invalid testClassName: must be a valid Java identifier: " + testClassName);
        }
        return runMaven(
                List.of("test", "-Dtest=" + testClassName, "--no-transfer-progress", "-q"),
                repoRoot,
                listener);
    }

    /**
     * Parses JaCoCo coverage CSV for the target class under test.
     *
     * @param repoRoot        Maven project root
     * @param targetClassName name of the class under test
     * @return percentage line coverage (0-100), or null if coverage file or class is absent
     */
    public Integer extractJaCoCoCoverage(Path repoRoot, String targetClassName) {
        return JaCoCoReportParser.extractCoverage(repoRoot, targetClassName);
    }

    /**
     * Runs a Maven command in the given working directory.
     *
     * <p>Prefers {@code mvnw.cmd} / {@code mvnw} (wrapper) if present in the
     * project root; falls back to the system {@code mvn} / {@code mvn.cmd}.
     * On Windows, {@code .cmd} files are invoked through {@code cmd /c} to
     * ensure the shell interprets the batch script correctly.
     *
     * @param args     Maven goal and option arguments (e.g. {@code ["test-compile", "-q"]})
     * @param workDir  directory in which to run Maven
     * @param listener progress listener for streaming stdout lines
     * @return combined exit code and stdout+stderr output
     */
    public ProcessResult runMaven(List<String> args, Path workDir, PipelineProgressListener listener)
            throws IOException, InterruptedException, TimeoutException {

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        Path wrapperFile = workDir.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        boolean hasWrapper = Files.exists(wrapperFile);

        List<String> command = new ArrayList<>();
        if (isWindows) {
            command.add("cmd");
            command.add("/c");
            command.add(hasWrapper ? "mvnw.cmd" : "mvn.cmd");
        } else {
            command.add(hasWrapper ? "./mvnw" : "mvn");
        }
        command.addAll(args);

        log.debug("Running: {} in {}", command, workDir);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);   // merge stderr into stdout

        Process process = pb.start();

        // Drain stdout+stderr on a separate thread to prevent deadlock.
        final StringBuilder outputBuffer = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuffer.append(line).append("\n");
                    if (listener != null) {
                        listener.onProgress("MAVEN", line);
                    }
                }
            } catch (IOException ignored) {
                // Process ended before we finished reading — output already in buffer
            }
        }, "maven-output-drainer");
        drainer.setDaemon(true);
        drainer.start();

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            log.error("Maven process timed out after {} seconds. Terminating process tree forcibly...", timeoutSeconds);
            try {
                process.descendants().forEach(handle -> {
                    if (handle != null) {
                        handle.destroyForcibly();
                    }
                });
            } catch (Exception e) {
                log.warn("Could not destroy process descendants: {}", e.getMessage());
            }
            process.destroyForcibly();
            drainer.join(5_000L);
            throw new TimeoutException("Maven process timed out after " + timeoutSeconds + " seconds");
        }
        drainer.join(30_000L); // wait up to 30 s for drainer to finish flushing

        int exitValue = process.exitValue();
        log.debug("Maven exit code: {}", exitValue);
        return new ProcessResult(exitValue, outputBuffer.toString());
    }



    // =========================================================================
    // Inner record
    // =========================================================================

    /**
     * Immutable result of a single Maven process invocation.
     *
     * @param exitCode process exit code (0 = success)
     * @param output   combined stdout + stderr text
     */
    public record ProcessResult(int exitCode, String output) {}
}
