package com.qe.agent.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaSourceSanitizer - dedicated pure-function helper for extracting,
 * sanitizing, and validating generated Java source code and static imports.
 */
public final class JavaSourceSanitizer {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\bpublic\\s+class\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern CODE_FENCE_PATTERN =
            Pattern.compile("```(?:[a-zA-Z]*)?\\s*\\r?\\n(.*?)\\r?\\n[ \\t]*```[ \\t]*(?:\\r?\\n|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE_LINE_PATTERN =
            Pattern.compile("^\\s*```[a-zA-Z]*\\s*$", Pattern.MULTILINE);

    private JavaSourceSanitizer() {
        // Utility class
    }

    /**
     * Strips markdown ```java ... ``` fences from LLM responses, returning only the inner code.
     * If no fence is found, returns the trimmed string.
     */
    public static String extractCodeBlock(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return "";
        }

        // Pass 1: try to extract content between ``` fences
        Matcher m = CODE_FENCE_PATTERN.matcher(llmResponse);
        String extracted = m.find() ? m.group(1) : llmResponse;

        // Pass 2: strip any residual fence lines the regex may have left at top or bottom
        String[] lines = extracted.split("\\r?\\n", -1);
        int start = 0;
        int end = lines.length - 1;
        while (start <= end && FENCE_LINE_PATTERN.matcher(lines[start]).matches()) start++;
        while (end >= start && FENCE_LINE_PATTERN.matcher(lines[end]).matches()) end--;

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    /**
     * Extracts the package name from a Java source string.
     * Returns an empty string if no package statement is found.
     */
    public static String extractPackageName(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) return "";
        Matcher m = PACKAGE_PATTERN.matcher(javaSource);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Ensures the test code contains a valid package declaration.
     * If the test code lacks a package declaration but the target source code has one,
     * prepends the target class's package declaration.
     */
    public static String ensurePackage(String testCode, String targetFileContent) {
        if (testCode == null || testCode.isBlank()) return testCode;
        String existingPackage = extractPackageName(testCode);
        if (!existingPackage.isEmpty()) {
            return testCode;
        }
        String targetPackage = extractPackageName(targetFileContent);
        if (!targetPackage.isEmpty()) {
            return "package " + targetPackage + ";\n\n" + testCode.stripLeading();
        }
        return testCode;
    }

    private static final Pattern JAVA_IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$]*$");

    /**
     * Validates whether a given string is a syntactically valid Java identifier.
     */
    public static boolean isValidJavaIdentifier(String name) {
        return name != null && JAVA_IDENTIFIER_PATTERN.matcher(name).matches();
    }

    /**
     * Extracts the first {@code public class} name from a Java source string.
     * Falls back to {@code "GeneratedTest"} if no match is found or if the extracted name is invalid.
     */
    public static String extractPublicClassName(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) return "GeneratedTest";
        Matcher m = CLASS_NAME_PATTERN.matcher(javaSource);
        if (m.find()) {
            String candidate = m.group(1);
            if (isValidJavaIdentifier(candidate)) {
                return candidate;
            }
        }
        return "GeneratedTest";
    }

    /**
     * Post-processing check for mandatory static imports.
     * Inserts missing JUnit Assertions or Mockito static imports directly below
     * the package declaration line if required.
     */
    public static String ensureStaticImports(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }

        boolean needsAssertions = !code.contains("import static org.junit.jupiter.api.Assertions.*");
        boolean containsMockito = code.contains("Mockito")
                || code.contains("@Mock")
                || code.contains("@InjectMocks")
                || code.contains("@Spy")
                || code.contains("@Captor")
                || code.contains("@MockitoBean")
                || code.contains("org.mockito");
        boolean needsMockito = containsMockito && !code.contains("import static org.mockito.Mockito.*");

        List<String> importsToAdd = new ArrayList<>();
        if (needsAssertions) {
            importsToAdd.add("import static org.junit.jupiter.api.Assertions.*;");
        }
        if (needsMockito) {
            importsToAdd.add("import static org.mockito.Mockito.*;");
        }

        // Always inject java.util.* wildcard as a safety net.
        // This is a no-op if it is already present and harmless alongside specific imports.
        // It guarantees that no java.util type (List, Map, Optional, Set, etc.) can ever
        // produce a "cannot find symbol" compile error due to a missing import.
        if (!code.contains("import java.util.*;")) {
            importsToAdd.add("import java.util.*;");
        }

        if (importsToAdd.isEmpty()) {
            return code;
        }

        String importBlock = String.join("\n", importsToAdd);

        Matcher m = PACKAGE_PATTERN.matcher(code);
        if (m.find()) {
            int insertPos = m.end();
            return code.substring(0, insertPos) + "\n\n" + importBlock + code.substring(insertPos);
        } else {
            return importBlock + "\n\n" + code;
        }
    }

    private static final Pattern DANGEROUS_PATTERNS = Pattern.compile(
            "\\b(Runtime\\.getRuntime\\s*\\(|ProcessBuilder|java\\.lang\\.Process\\b|System\\.exit\\s*\\(|System\\.load(Library)?\\s*\\(|System\\.setSecurityManager|java\\.lang\\.reflect\\.|java\\.lang\\.invoke\\.MethodHandles|sun\\.misc\\.Unsafe|sun\\.reflect\\.|jdk\\.internal\\.|ClassLoader|URLClassLoader|java\\.net\\.(Socket|ServerSocket|URL|URI|http)|javax\\.net\\.|javax\\.naming\\.(InitialContext|Context)|javax\\.script\\.(ScriptEngine|ScriptEngineManager)|java\\.lang\\.instrument\\.|java\\.io\\.(File|FileInputStream|FileOutputStream|RandomAccessFile|FileReader|FileWriter)|java\\.nio\\.file\\.(Files|Paths|Path|FileSystem|FileSystems))|ldap://|rmi://",
            Pattern.CASE_INSENSITIVE);

    /**
     * Inspects generated Java test code for dangerous APIs (process spawning,
     * network socket connections, reflection abuse, JNDI lookups, or system termination).
     *
     * @param code generated Java source code
     * @throws SecurityException if prohibited APIs or patterns are detected
     */
    public static void validateSafety(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        Matcher m = DANGEROUS_PATTERNS.matcher(code);
        if (m.find()) {
            throw new SecurityException("Generated test code failed security validation: prohibited API pattern detected: " + m.group());
        }
    }
}

