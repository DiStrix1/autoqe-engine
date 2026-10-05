package com.qe.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload returned by POST /api/v1/generate-tests.
 *
 * <p>CONTRACT (PROJECT_SPECIFICATION.md Section 4.2):
 * <pre>
 * {
 *   "targetClass": "UserService",
 *   "status": "PASSED",
 *   "attempts": 2,
 *   "compilationSuccessful": true,
 *   "generatedTestCode": "package com.example; ...",
 *   "executionLogs": "Tests run: 4, Failures: 0, Errors: 0"
 * }
 * </pre>
 *
 * <p>Status values (PROJECT_SPECIFICATION.md Section 5):
 * <ul>
 *   <li>{@code PASSED} - tests compiled and ran successfully.</li>
 *   <li>{@code FAILED_AFTER_HEALING} - all 3 self-healing retries exhausted.</li>
 *   <li>{@code PRE_COMPILE_FAILED} - target source code fails mvn test-compile.</li>
 *   <li>{@code GENERATION_FAILED} - LLM could not produce test code.</li>
 *   <li>{@code FILE_WRITE_ERROR} - could not write test file to disk.</li>
 *   <li>{@code FILE_NOT_FOUND} - targetFilePath does not exist.</li>
 *   <li>{@code ERROR} - unexpected runtime error.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationResponse {

    /** Fully qualified or simple name of the class under test. */
    private String targetClass;

    /** Outcome code string. See Javadoc above for possible values. */
    private String status;

    /** Total number of execution attempts (including self-healing retries). */
    private int attempts;

    /** Whether the final generated test compiled without errors. */
    private boolean compilationSuccessful;

    /** The last version of the generated JUnit 5 test source code. */
    private String generatedTestCode;

    /** Combined stdout/stderr from the last Maven execution. */
    private String executionLogs;

    /** Real code coverage percentage (0-100) extracted from JaCoCo, or null if not available. */
    private Integer coverage;

    public void setStatus(TestStatus testStatus) {
        this.status = testStatus != null ? testStatus.getValue() : null;
    }

    public static class TestGenerationResponseBuilder {
        public TestGenerationResponseBuilder status(TestStatus testStatus) {
            this.status = testStatus != null ? testStatus.getValue() : null;
            return this;
        }

        public TestGenerationResponseBuilder status(String status) {
            this.status = status;
            return this;
        }
    }
}
