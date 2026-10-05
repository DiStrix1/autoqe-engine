package com.qe.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TestRunnerService} and {@link JavaSourceSanitizer}.
 *
 * <p>Pure string and file logic tests with no Spring context required.
 */
class TestRunnerServiceTest {

    private final TestRunnerService service = new TestRunnerService(120);

    // =========================================================================
    // extractCodeBlock (JavaSourceSanitizer)
    // =========================================================================

    @Test
    void extractCodeBlock_withJavaFence_returnsInnerCode() {
        String input = "```java\npublic class Foo {}\n```";
        String result = JavaSourceSanitizer.extractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_withPlainFence_returnsInnerCode() {
        String input = "```\npublic class Foo {}\n```";
        String result = JavaSourceSanitizer.extractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_noFence_returnsTrimmedInput() {
        String input = "  public class Foo {}  ";
        String result = JavaSourceSanitizer.extractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_nullInput_returnsEmptyString() {
        String result = JavaSourceSanitizer.extractCodeBlock(null);
        assertEquals("", result);
    }

    @Test
    void extractCodeBlock_blankInput_returnsEmptyString() {
        String result = JavaSourceSanitizer.extractCodeBlock("   ");
        assertEquals("", result);
    }

    @Test
    void extractCodeBlock_fenceWithExtraResidualLine_stripsResidual() {
        // Simulate llama3 output quirk: ```java appears as first line inside captured group
        String input = "```java\n```java\npublic class Foo {}\n```";
        String result = JavaSourceSanitizer.extractCodeBlock(input);
        // Should not start with ```
        assertFalse(result.startsWith("```"), "Residual fence line should be stripped");
        assertTrue(result.contains("public class Foo {}"));
    }

    // =========================================================================
    // extractPackageName (JavaSourceSanitizer)
    // =========================================================================

    @Test
    void extractPackageName_validPackageStatement_returnsPackageName() {
        String code = "package com.qe.demo;\n\npublic class Foo {}";
        String pkg = JavaSourceSanitizer.extractPackageName(code);
        assertEquals("com.qe.demo", pkg);
    }

    @Test
    void extractPackageName_noPackageStatement_returnsEmptyString() {
        String code = "public class Foo {}";
        String pkg = JavaSourceSanitizer.extractPackageName(code);
        assertEquals("", pkg);
    }

    // =========================================================================
    // extractPublicClassName (JavaSourceSanitizer)
    // =========================================================================

    @Test
    void extractPublicClassName_validPublicClass_returnsClassName() {
        String code = "package com.qe.demo;\npublic class UserServiceTest {}";
        String name = JavaSourceSanitizer.extractPublicClassName(code);
        assertEquals("UserServiceTest", name);
    }

    @Test
    void extractPublicClassName_noPublicClass_returnsDefaultFallback() {
        String code = "class Hidden {}";
        String name = JavaSourceSanitizer.extractPublicClassName(code);
        assertEquals("GeneratedTest", name);
    }

    // =========================================================================
    // findMavenRoot
    // =========================================================================

    @Test
    void findMavenRoot_pomXmlInParentDirectory_returnsCorrectRoot(@TempDir Path tempDir)
            throws Exception {
        // Create a fake Maven project: tempDir/pom.xml + tempDir/src/main/java/MyClass.java
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path srcDir = tempDir.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Path javaFile = srcDir.resolve("MyClass.java");
        Files.writeString(javaFile, "public class MyClass {}");

        Path root = invokeFindMavenRoot(javaFile);

        assertEquals(tempDir.toAbsolutePath(), root.toAbsolutePath());
    }

    @Test
    void findMavenRoot_noPomXmlAnywhere_throwsIllegalArgumentException(@TempDir Path tempDir)
            throws Exception {
        // No pom.xml created — should throw
        Path orphanFile = tempDir.resolve("Orphan.java");
        Files.writeString(orphanFile, "public class Orphan {}");

        assertThrows(Exception.class, () -> invokeFindMavenRoot(orphanFile));
    }

    // =========================================================================
    // ensureStaticImports (JavaSourceSanitizer)
    // =========================================================================

    @Test
    void ensureStaticImports_missingBoth_injectsAssertionsAndMockito() {
        String code = "package com.qe.demo;\n\nimport org.mockito.Mock;\n\npublic class FooTest {}";
        String result = JavaSourceSanitizer.ensureStaticImports(code);

        assertTrue(result.contains("import static org.junit.jupiter.api.Assertions.*;"),
                "Should inject Assertions import");
        assertTrue(result.contains("import static org.mockito.Mockito.*;"),
                "Should inject Mockito import");
    }

    @Test
    void ensureStaticImports_bothPresent_returnsCodeUnchanged() {
        String code = "package com.qe.demo;\n\n"
                + "import java.util.*;\n\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import static org.mockito.Mockito.*;\n\n"
                + "public class FooTest {}";
        String result = JavaSourceSanitizer.ensureStaticImports(code);
        assertEquals(code, result);
    }

    @Test
    void ensureStaticImports_noMockitoUsage_doesNotInjectMockitoImport() {
        String code = "package com.qe.demo;\n\npublic class FooTest {}";
        String result = JavaSourceSanitizer.ensureStaticImports(code);

        assertFalse(result.contains("import static org.mockito.Mockito.*;"),
                "Should NOT inject Mockito import when no Mockito usage detected");
    }

    // =========================================================================
    // MAX_RETRY_ATTEMPTS constant
    // =========================================================================

    @Test
    void maxRetryAttempts_isThree() {
        assertEquals(3, TestRunnerService.MAX_RETRY_ATTEMPTS);
    }

    // =========================================================================
    // extractJaCoCoCoverage
    // =========================================================================

    @Test
    void extractJaCoCoCoverage_missingCsv_returnsNull(@TempDir Path tempDir) {
        Integer coverage = service.extractJaCoCoCoverage(tempDir, "UserService");
        assertNull(coverage, "Missing CSV should return null");
    }

    @Test
    void extractJaCoCoCoverage_validCsv_computesAccuratePercentage(@TempDir Path tempDir) throws Exception {
        Path jacocoDir = tempDir.resolve("target").resolve("site").resolve("jacoco");
        Files.createDirectories(jacocoDir);
        Path csvFile = jacocoDir.resolve("jacoco.csv");

        // UserService has 30 covered lines and 10 missed lines => 30 / (30 + 10) = 75%
        String csvContent = """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                TestGroup,com.qe.demo,UserService,0,100,0,10,10,30,0,5,0,3
                TestGroup,com.qe.demo,OrderService,5,50,2,8,2,18,1,4,0,2
                """;
        Files.writeString(csvFile, csvContent);

        Integer coverage = service.extractJaCoCoCoverage(tempDir, "UserService");
        assertNotNull(coverage);
        assertEquals(75, coverage.intValue());
    }

    @Test
    void extractJaCoCoCoverage_withInnerClasses_aggregatesLines(@TempDir Path tempDir) throws Exception {
        Path jacocoDir = tempDir.resolve("target").resolve("site").resolve("jacoco");
        Files.createDirectories(jacocoDir);
        Path csvFile = jacocoDir.resolve("jacoco.csv");

        // SessionManager: 6 missed, 20 covered
        // SessionManager.SessionInfo: 0 missed, 4 covered
        // Total covered = 20 + 4 = 24. Total missed = 6 + 0 = 6. Total = 30. (24 * 100) / 30 = 80%.
        String csvContent = """
                GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
                TestGroup,com.qe.demo,SessionManager,10,80,2,6,6,20,2,4,0,3
                TestGroup,com.qe.demo,SessionManager.SessionInfo,0,20,0,0,0,4,0,1,0,1
                """;
        Files.writeString(csvFile, csvContent);

        Integer coverage = service.extractJaCoCoCoverage(tempDir, "SessionManager");
        assertNotNull(coverage);
        assertEquals(80, coverage.intValue());
    }

    // =========================================================================
    // validateSafety (JavaSourceSanitizer)
    // =========================================================================

    @Test
    void validateSafety_safeCode_passesWithoutException() {
        String safeCode = "package com.qe.demo;\n\npublic class SafeTest {\n    @Test\n    void test() {\n        assertEquals(2, 1 + 1);\n    }\n}";
        assertDoesNotThrow(() -> JavaSourceSanitizer.validateSafety(safeCode));
    }

    @Test
    void validateSafety_fileIo_throwsSecurityException() {
        String dangerousCode = "package com.qe.demo;\n\npublic class LeakTest {\n    void leak() throws Exception {\n        java.nio.file.Files.readString(null);\n    }\n}";
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(dangerousCode));
    }

    @Test
    void validateSafety_processSpawning_throwsSecurityException() {
        String dangerousCode = "package com.qe.demo;\n\npublic class ExploitTest {\n    void run() {\n        Runtime.getRuntime().exec(\"calc\");\n    }\n}";
        assertThrows(SecurityException.class, () -> JavaSourceSanitizer.validateSafety(dangerousCode));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Path invokeFindMavenRoot(Path start) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("findMavenRoot", Path.class);
        m.setAccessible(true);
        try {
            return (Path) m.invoke(service, start);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw (Exception) ite.getCause();
        }
    }
}
