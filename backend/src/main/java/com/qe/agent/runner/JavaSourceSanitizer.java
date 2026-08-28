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
     * Extracts the first {@code public class} name from a Java source string.
     * Falls back to {@code "GeneratedTest"} if no match is found.
     */
    public static String extractPublicClassName(String javaSource) {
        if (javaSource == null || javaSource.isBlank()) return "GeneratedTest";
        Matcher m = CLASS_NAME_PATTERN.matcher(javaSource);
        return m.find() ? m.group(1) : "GeneratedTest";
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

        if (!needsAssertions && !needsMockito) {
            return code;
        }

        List<String> importsToAdd = new ArrayList<>();
        if (needsAssertions) {
            importsToAdd.add("import static org.junit.jupiter.api.Assertions.*;");
        }
        if (needsMockito) {
            importsToAdd.add("import static org.mockito.Mockito.*;");
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
}
