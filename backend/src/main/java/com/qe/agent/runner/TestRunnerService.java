package com.qe.agent.runner;

import com.qe.agent.agents.GeneratorAgent;
import com.qe.agent.model.TestGenerationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TestRunnerService - self-healing test execution engine.
 *
 * <p>Implements the exact loop logic specified in PROJECT_SPECIFICATION.md Section 5:
 *
 * <ol>
 *   <li><b>Pre-Compile Check:</b> runs {@code mvn test-compile} on the target project
 *       via {@link ProcessBuilder}. Aborts immediately if the target source fails.</li>
 *   <li><b>Write File:</b> writes the generated test code to the correct
 *       {@code src/test/java/&lt;package&gt;/} directory under the Maven project root.</li>
 *   <li><b>Execute Command:</b> runs {@code mvn test -Dtest=&lt;ClassName&gt;} and
 *       captures combined stdout/stderr.</li>
 *   <li><b>Evaluate Exit Code:</b> exit 0 = PASSED; non-zero triggers Step 5.</li>
 *   <li><b>Trigger Repair Prompt:</b> sends original class code, failing test, and full
 *       Maven output to {@link GeneratorAgent#repairTests} for LLM-driven correction.</li>
 *   <li><b>Retry Loop:</b> retries up to {@link #MAX_RETRY_ATTEMPTS} (3). Returns
 *       {@code FAILED_AFTER_HEALING} if all attempts are exhausted.</li>
 * </ol>
 *
 * <p>Safety guardrail (Section 6.3): the method root is derived by walking up the
 * directory tree from {@code targetFilePath} until a {@code pom.xml} is found.
 * This prevents ProcessBuilder from running Maven in the wrong directory.
 */
@Slf4j
@Service
public class TestRunnerService {

    /**
     * Hard upper bound on self-healing retries (PROJECT_SPECIFICATION.md Section 5, Step 6).
     * Public so callers (e.g. TestGenerationController) can log the accurate value without
     * duplicating the literal.
     */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    private final GeneratorAgent generatorAgent;

    public TestRunnerService(GeneratorAgent generatorAgent) {
        this.generatorAgent = generatorAgent;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Executes the full self-healing pipeline for the supplied generated test code.
     *
     * @param targetClassName   simple or fully qualified name of the class under test
     * @param targetFilePath    absolute path to the target {@code .java} source file
     * @param targetFileContent raw source text of the class under test
     * @param rawGeneratedCode  raw LLM output (may include ```java fences)
     * @param codeContext       retrieval context (unused in retry loop; kept for future use)
     * @return populated {@link TestGenerationResponse} reflecting final outcome
     */
    public TestGenerationResponse executeWithSelfHealing(
            String targetClassName,
            String targetFilePath,
            String targetFileContent,
            String rawGeneratedCode,
            String codeContext) {

        String currentTestCode = extractCodeBlock(rawGeneratedCode);

        // Track written test file path so we can clean it up on failure (Improvement #C).
        final AtomicReference<Path> writtenTestFile = new AtomicReference<>(null);

        // Determine Maven project root from the target file path.
        Path repoRoot;
        try {
            repoRoot = findMavenRoot(Paths.get(targetFilePath));
        } catch (IllegalArgumentException e) {
            log.error("Cannot locate Maven root from path '{}': {}", targetFilePath, e.getMessage());
            return TestGenerationResponse.builder()
                    .targetClass(targetClassName)
                    .status("ERROR")
                    .attempts(0)
                    .compilationSuccessful(false)
                    .generatedTestCode(currentTestCode)
                    .executionLogs("Cannot locate Maven project root: " + e.getMessage())
                    .build();
        }

        // ── Step 1: Pre-compile check ──────────────────────────────────────────
        log.info("Step 1: Pre-compile check on target project at '{}'.", repoRoot);
        try {
            ProcessResult preCompile = runMaven(List.of("test-compile", "-q"), repoRoot);
            if (preCompile.exitCode() != 0) {
                log.error("Pre-compile check FAILED (exit {}).", preCompile.exitCode());
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("PRE_COMPILE_FAILED")
                        .attempts(0)
                        .compilationSuccessful(false)
                        .generatedTestCode(currentTestCode)
                        .executionLogs("Target source failed mvn test-compile:\n" + preCompile.output())
                        .build();
            }
        } catch (Exception e) {
            log.error("Pre-compile check threw exception: {}", e.getMessage(), e);
            return TestGenerationResponse.builder()
                    .targetClass(targetClassName)
                    .status("PRE_COMPILE_ERROR")
                    .attempts(0)
                    .compilationSuccessful(false)
                    .generatedTestCode(currentTestCode)
                    .executionLogs("Pre-compile error: " + e.getMessage())
                    .build();
        }
        log.info("Pre-compile check PASSED.");

        // ── Steps 2-6: Self-healing execution loop ────────────────────────────
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            log.info("Execution attempt {}/{}.", attempt, MAX_RETRY_ATTEMPTS);

            // Step 2: Write test file
            String testClassName;
            try {
                currentTestCode = writeTestFile(currentTestCode, repoRoot);
                testClassName = extractPublicClassName(currentTestCode);
                // Record written path for potential cleanup on failure (Improvement #C)
                String subPath = extractPackageName(currentTestCode).replace('.', '/');
                Path testDir = repoRoot.resolve("src").resolve("test").resolve("java");
                if (!subPath.isEmpty()) testDir = testDir.resolve(subPath);
                writtenTestFile.set(testDir.resolve(testClassName + ".java"));
                log.info("Test file written: {}.java", testClassName);
            } catch (Exception e) {
                log.error("Failed to write test file: {}", e.getMessage(), e);
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("FILE_WRITE_ERROR")
                        .attempts(attempt)
                        .compilationSuccessful(false)
                        .generatedTestCode(currentTestCode)
                        .executionLogs("Error writing test file: " + e.getMessage())
                        .build();
            }

            // Step 3: Execute test
            ProcessResult testResult;
            try {
                testResult = runMaven(
                        List.of("test", "-Dtest=" + testClassName,
                                "--no-transfer-progress", "-q"),
                        repoRoot);
            } catch (Exception e) {
                log.error("Maven test execution threw exception: {}", e.getMessage(), e);
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("EXECUTION_ERROR")
                        .attempts(attempt)
                        .compilationSuccessful(false)
                        .generatedTestCode(currentTestCode)
                        .executionLogs("Error executing Maven test: " + e.getMessage())
                        .build();
            }

            // Step 4: Evaluate exit code
            if (testResult.exitCode() == 0) {
                log.info("Test PASSED on attempt {}.", attempt);
                // PASSED: keep the test file on disk.
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("PASSED")
                        .attempts(attempt)
                        .compilationSuccessful(true)
                        .generatedTestCode(currentTestCode)
                        .executionLogs(testResult.output())
                        .build();
            }

            log.warn("Test FAILED on attempt {} (exit {}). Log:\n{}",
                    attempt, testResult.exitCode(), testResult.output());

            if (attempt == MAX_RETRY_ATTEMPTS) {
                log.error("All {} attempts exhausted. Returning FAILED_AFTER_HEALING.", MAX_RETRY_ATTEMPTS);
                cleanupTestFile(writtenTestFile.get());
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("FAILED_AFTER_HEALING")
                        .attempts(attempt)
                        .compilationSuccessful(false)
                        .generatedTestCode(currentTestCode)
                        .executionLogs(testResult.output())
                        .build();
            }

            // Step 5: Trigger repair prompt
            log.info("Triggering LLM self-healing repair (attempt {} of {})...", attempt, MAX_RETRY_ATTEMPTS - 1);
            try {
                String repaired = generatorAgent.repairTests(
                        targetFileContent,
                        currentTestCode,
                        testResult.output()
                );
                currentTestCode = extractCodeBlock(repaired);
                log.info("Repaired test code received from GeneratorAgent.");
            } catch (Exception e) {
                log.error("GeneratorAgent.repairTests failed: {}", e.getMessage(), e);
                cleanupTestFile(writtenTestFile.get());
                return TestGenerationResponse.builder()
                        .targetClass(targetClassName)
                        .status("FAILED_AFTER_HEALING")
                        .attempts(attempt)
                        .compilationSuccessful(false)
                        .generatedTestCode(currentTestCode)
                        .executionLogs(testResult.output()
                                + "\n\n[Self-healing agent error]: " + e.getMessage())
                        .build();
            }
        }

        // Defensive fallthrough (loop always returns inside)
        return TestGenerationResponse.builder()
                .targetClass(targetClassName)
                .status("FAILED_AFTER_HEALING")
                .attempts(MAX_RETRY_ATTEMPTS)
                .compilationSuccessful(false)
                .generatedTestCode(currentTestCode)
                .executionLogs("Max retries exhausted.")
                .build();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Walks up the directory tree from {@code start} to find the nearest
     * directory containing a {@code pom.xml}.
     *
     * @param start a file or directory inside the Maven project
     * @return path to the Maven project root
     * @throws IllegalArgumentException if no {@code pom.xml} is found
     */
    private Path findMavenRoot(Path start) {
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
     * @param testCode  complete Java source code of the test class
     * @param repoRoot  Maven project root
     * @throws IOException if the directory cannot be created or the file cannot be written
     */
    private String writeTestFile(String testCode, Path repoRoot) throws IOException {
        testCode = ensureStaticImports(testCode);
        String packageName = extractPackageName(testCode);
        String className   = extractPublicClassName(testCode);
        String subPath     = packageName.isEmpty() ? "" : packageName.replace('.', '/');

        Path testDir = repoRoot.resolve("src").resolve("test").resolve("java");
        if (!subPath.isEmpty()) {
            testDir = testDir.resolve(subPath);
        }
        Files.createDirectories(testDir);

        Path testFile = testDir.resolve(className + ".java");
        Files.writeString(testFile, testCode, StandardCharsets.UTF_8);
        log.debug("Wrote {} bytes to: {}", testCode.length(), testFile.toAbsolutePath());
        return testCode;
    }

    /**
     * Deletes the generated test file from disk after a failed/aborted run.
     *
     * <p>Improvement #C: prevents stale broken test files from accumulating
     * in the sandbox's test directory across multiple generation attempts.
     * Files are only kept on disk when the test run {@code PASSED}.
     *
     * @param path path to the test file, or {@code null} if no file was written
     */
    private void cleanupTestFile(Path path) {
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
     * Post-processing check for mandatory static imports.
     * Inserts missing JUnit Assertions or Mockito static imports directly below
     * the package declaration line if required.
     *
     * <p>Improvement #H: expanded Mockito detection to cover @InjectMocks, @Spy,
     * @Captor, and @MockitoBean in addition to the original @Mock check.
     */
    private String ensureStaticImports(String code) {
        return JavaSourceSanitizer.ensureStaticImports(code);
    }

    /**
     * Runs a Maven command in the given working directory.
     *
     * <p>Prefers {@code mvnw.cmd} / {@code mvnw} (wrapper) if present in the
     * project root; falls back to the system {@code mvn} / {@code mvn.cmd}.
     * On Windows, {@code .cmd} files are invoked through {@code cmd /c} to
     * ensure the shell interprets the batch script correctly.
     *
     * @param args    Maven goal and option arguments (e.g. {@code ["test-compile", "-q"]})
     * @param workDir directory in which to run Maven
     * @return combined exit code and stdout+stderr output
     */
    private ProcessResult runMaven(List<String> args, Path workDir)
            throws IOException, InterruptedException {

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
        // If the OS output buffer fills up before process.waitFor() returns,
        // the child process blocks writing and the parent blocks waiting → deadlock.
        // The background thread reads continuously so the buffer never fills.
        final StringBuilder outputBuffer = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try {
                byte[] bytes = process.getInputStream().readAllBytes();
                outputBuffer.append(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Process ended before we finished reading — output already in buffer
            }
        }, "maven-output-drainer");
        drainer.setDaemon(true);
        drainer.start();

        int exitCode = process.waitFor();
        drainer.join(30_000L); // wait up to 30 s for drainer to finish flushing

        log.debug("Maven exit code: {}", exitCode);
        return new ProcessResult(exitCode, outputBuffer.toString());
    }

    /**
     * Extracts the package name from a Java source string.
     * Returns an empty string if no package statement is found.
     */
    private String extractPackageName(String javaSource) {
        return JavaSourceSanitizer.extractPackageName(javaSource);
    }

    /**
     * Extracts the first {@code public class} name from a Java source string.
     * Falls back to {@code "GeneratedTest"} if no match is found.
     */
    private String extractPublicClassName(String javaSource) {
        return JavaSourceSanitizer.extractPublicClassName(javaSource);
    }

    /**
     * Strips a markdown {@code ```java ... ```} (or bare {@code ``` ... ```}) fence
     * from an LLM response, returning only the inner code.
     * If no fence is found, the response is returned as-is after trimming.
     */
    private String extractCodeBlock(String llmResponse) {
        return JavaSourceSanitizer.extractCodeBlock(llmResponse);
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
    record ProcessResult(int exitCode, String output) {}
}
