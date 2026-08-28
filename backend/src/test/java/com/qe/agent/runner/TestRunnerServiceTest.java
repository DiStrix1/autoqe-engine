package com.qe.agent.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the pure-logic helper methods in {@link TestRunnerService}.
 *
 * <p>The private methods are tested via reflection because they contain
 * critical string-processing logic that is too important to leave uncovered:
 * a bug in {@code extractCodeBlock} silently passes garbage code to Maven.
 *
 * <p>No Spring context is needed — these are pure string/file logic tests.
 */
class TestRunnerServiceTest {

    // We need a GeneratorAgent to construct TestRunnerService; use a Mockito mock.
    private final com.qe.agent.agents.GeneratorAgent mockAgent =
            mock(com.qe.agent.agents.GeneratorAgent.class);

    private final TestRunnerService service = new TestRunnerService(mockAgent);

    // =========================================================================
    // extractCodeBlock (via reflection)
    // =========================================================================

    @Test
    void extractCodeBlock_withJavaFence_returnsInnerCode() throws Exception {
        String input = "```java\npublic class Foo {}\n```";
        String result = invokeExtractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_withPlainFence_returnsInnerCode() throws Exception {
        String input = "```\npublic class Foo {}\n```";
        String result = invokeExtractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_noFence_returnsTrimmedInput() throws Exception {
        String input = "  public class Foo {}  ";
        String result = invokeExtractCodeBlock(input);
        assertEquals("public class Foo {}", result);
    }

    @Test
    void extractCodeBlock_nullInput_returnsEmptyString() throws Exception {
        String result = invokeExtractCodeBlock(null);
        assertEquals("", result);
    }

    @Test
    void extractCodeBlock_blankInput_returnsEmptyString() throws Exception {
        String result = invokeExtractCodeBlock("   ");
        assertEquals("", result);
    }

    @Test
    void extractCodeBlock_fenceWithExtraResidualLine_stripsResidual() throws Exception {
        // Simulate llama3 output quirk: ```java appears as first line inside captured group
        String input = "```java\n```java\npublic class Foo {}\n```";
        String result = invokeExtractCodeBlock(input);
        // Should not start with ```
        assertFalse(result.startsWith("```"), "Residual fence line should be stripped");
        assertTrue(result.contains("public class Foo {}"));
    }

    // =========================================================================
    // extractPackageName (via reflection)
    // =========================================================================

    @Test
    void extractPackageName_validPackageStatement_returnsPackageName() throws Exception {
        String code = "package com.qe.demo;\n\npublic class Foo {}";
        String pkg = invokeExtractPackageName(code);
        assertEquals("com.qe.demo", pkg);
    }

    @Test
    void extractPackageName_noPackageStatement_returnsEmptyString() throws Exception {
        String code = "public class Foo {}";
        String pkg = invokeExtractPackageName(code);
        assertEquals("", pkg);
    }

    // =========================================================================
    // extractPublicClassName (via reflection)
    // =========================================================================

    @Test
    void extractPublicClassName_validPublicClass_returnsClassName() throws Exception {
        String code = "package com.qe.demo;\npublic class UserServiceTest {}";
        String name = invokeExtractPublicClassName(code);
        assertEquals("UserServiceTest", name);
    }

    @Test
    void extractPublicClassName_noPublicClass_returnsDefaultFallback() throws Exception {
        String code = "class Hidden {}";
        String name = invokeExtractPublicClassName(code);
        assertEquals("GeneratedTest", name);
    }

    // =========================================================================
    // findMavenRoot (via reflection)
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
    // ensureStaticImports (via reflection)
    // =========================================================================

    @Test
    void ensureStaticImports_missingBoth_injectsAssertionsAndMockito() throws Exception {
        String code = "package com.qe.demo;\n\nimport org.mockito.Mock;\n\npublic class FooTest {}";
        String result = invokeEnsureStaticImports(code);

        assertTrue(result.contains("import static org.junit.jupiter.api.Assertions.*;"),
                "Should inject Assertions import");
        assertTrue(result.contains("import static org.mockito.Mockito.*;"),
                "Should inject Mockito import");
    }

    @Test
    void ensureStaticImports_bothPresent_returnsCodeUnchanged() throws Exception {
        String code = "package com.qe.demo;\n\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import static org.mockito.Mockito.*;\n\n"
                + "public class FooTest {}";
        String result = invokeEnsureStaticImports(code);
        assertEquals(code, result);
    }

    @Test
    void ensureStaticImports_noMockitoUsage_doesNotInjectMockitoImport() throws Exception {
        String code = "package com.qe.demo;\n\npublic class FooTest {}";
        String result = invokeEnsureStaticImports(code);

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
    // Reflection helpers
    // =========================================================================

    private String invokeExtractCodeBlock(String input) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("extractCodeBlock", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, input);
    }

    private String invokeExtractPackageName(String input) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("extractPackageName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, input);
    }

    private String invokeExtractPublicClassName(String input) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("extractPublicClassName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, input);
    }

    private Path invokeFindMavenRoot(Path start) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("findMavenRoot", Path.class);
        m.setAccessible(true);
        try {
            return (Path) m.invoke(service, start);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw (Exception) ite.getCause();
        }
    }

    private String invokeEnsureStaticImports(String input) throws Exception {
        Method m = TestRunnerService.class.getDeclaredMethod("ensureStaticImports", String.class);
        m.setAccessible(true);
        return (String) m.invoke(service, input);
    }
}
