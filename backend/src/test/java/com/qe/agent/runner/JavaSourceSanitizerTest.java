package com.qe.agent.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JavaSourceSanitizerTest - unit tests for code extraction, static imports,
 * and security validation against prohibited API usage.
 */
class JavaSourceSanitizerTest {

    @Test
    @DisplayName("Extract code block strips markdown fences")
    void testExtractCodeBlock() {
        String input = """
                Here is the test:
                ```java
                package com.qe.demo;
                public class DemoTest {}
                ```
                """;
        String extracted = JavaSourceSanitizer.extractCodeBlock(input);
        assertEquals("package com.qe.demo;\npublic class DemoTest {}", extracted);
    }

    @Test
    @DisplayName("Extract code block without fences returns trimmed content")
    void testExtractCodeBlockWithoutFences() {
        String input = "  package com.qe.demo; public class DemoTest {}  ";
        String extracted = JavaSourceSanitizer.extractCodeBlock(input);
        assertEquals("package com.qe.demo; public class DemoTest {}", extracted);
    }

    @Test
    @DisplayName("Ensure package prepends target package if missing")
    void testEnsurePackage() {
        String targetSource = "package com.qe.service;\npublic class UserService {}";
        String testCode = "public class UserServiceTest {}";
        String withPackage = JavaSourceSanitizer.ensurePackage(testCode, targetSource);
        assertTrue(withPackage.startsWith("package com.qe.service;"));
    }

    @Test
    @DisplayName("Ensure static imports adds JUnit Assertions when missing")
    void testEnsureStaticImports() {
        String testCode = "package com.qe.demo;\npublic class SampleTest {}";
        String enriched = JavaSourceSanitizer.ensureStaticImports(testCode);
        assertTrue(enriched.contains("import static org.junit.jupiter.api.Assertions.*;"));
    }

    @Test
    @DisplayName("Safe test code passes security validation")
    void testValidateSafety_PassesSafeCode() {
        String safeTest = """
                package com.qe.demo;
                import static org.junit.jupiter.api.Assertions.*;
                import org.junit.jupiter.api.Test;

                public class CalculatorTest {
                    @Test
                    void testAdd() {
                        int a = 2;
                        int b = 3;
                        int expected = 5;
                        assertEquals(expected, a + b);
                    }
                }
                """;
        assertDoesNotThrow(() -> JavaSourceSanitizer.validateSafety(safeTest));
    }

    @Test
    @DisplayName("validateSafety rejects Runtime.getRuntime().exec()")
    void testValidateSafety_RejectsRuntimeExec() {
        String maliciousTest = """
                package com.qe.demo;
                public class ExploitTest {
                    static {
                        try {
                            Runtime.getRuntime().exec("calc.exe");
                        } catch (Exception ignored) {}
                    }
                }
                """;
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(maliciousTest));
    }

    @Test
    @DisplayName("validateSafety rejects ProcessBuilder")
    void testValidateSafety_RejectsProcessBuilder() {
        String maliciousTest = """
                package com.qe.demo;
                public class ExploitTest {
                    void run() {
                        new ProcessBuilder("cmd.exe").start();
                    }
                }
                """;
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(maliciousTest));
    }

    @Test
    @DisplayName("validateSafety rejects System.exit")
    void testValidateSafety_RejectsSystemExit() {
        String maliciousTest = """
                package com.qe.demo;
                public class ExploitTest {
                    void shutdown() {
                        System.exit(1);
                    }
                }
                """;
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(maliciousTest));
    }

    @Test
    @DisplayName("validateSafety rejects network socket creation")
    void testValidateSafety_RejectsSocket() {
        String maliciousTest = """
                package com.qe.demo;
                import java.net.Socket;
                public class ExploitTest {
                    void connect() {
                        Socket s = new Socket("attacker.com", 4444);
                    }
                }
                """;
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(maliciousTest));
    }

    @Test
    @DisplayName("validateSafety rejects reflection access")
    void testValidateSafety_RejectsReflection() {
        String maliciousTest = """
                package com.qe.demo;
                public class ExploitTest {
                    void hack() {
                        java.lang.reflect.Field f = null;
                    }
                }
                """;
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(maliciousTest));
    }
}
