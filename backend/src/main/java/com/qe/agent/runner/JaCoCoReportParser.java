package com.qe.agent.runner;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JaCoCoReportParser - dedicated utility for extracting code coverage metrics
 * from JaCoCo CSV execution reports.
 */
@Slf4j
public final class JaCoCoReportParser {

    private JaCoCoReportParser() {
        // Utility class
    }

    /**
     * Parses JaCoCo coverage CSV for the target class under test.
     *
     * @param repoRoot        Maven project root
     * @param targetClassName name of the class under test (simple or fully-qualified)
     * @return percentage line coverage (0-100), or null if coverage file or class is absent
     */
    public static Integer extractCoverage(Path repoRoot, String targetClassName) {
        if (repoRoot == null || targetClassName == null || targetClassName.isBlank()) {
            return null;
        }

        Path jacocoCsv = repoRoot.resolve("target").resolve("site").resolve("jacoco").resolve("jacoco.csv");
        if (!Files.exists(jacocoCsv)) {
            log.debug("No JaCoCo CSV found at: {}", jacocoCsv);
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(jacocoCsv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return null;
            }

            String[] headers = headerLine.split(",");
            int classIdx = -1, coveredIdx = -1, missedIdx = -1;

            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim();
                if (h.equalsIgnoreCase("CLASS")) classIdx = i;
                else if (h.equalsIgnoreCase("LINE_COVERED")) coveredIdx = i;
                else if (h.equalsIgnoreCase("LINE_MISSED")) missedIdx = i;
            }

            if (classIdx == -1 || coveredIdx == -1 || missedIdx == -1) {
                // Fallback to instruction coverage columns if line coverage is missing
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].trim();
                    if (h.equalsIgnoreCase("INSTRUCTION_COVERED")) coveredIdx = i;
                    else if (h.equalsIgnoreCase("INSTRUCTION_MISSED")) missedIdx = i;
                }
            }

            if (classIdx == -1 || coveredIdx == -1 || missedIdx == -1) {
                return null;
            }

            String simpleName = targetClassName.contains(".")
                    ? targetClassName.substring(targetClassName.lastIndexOf('.') + 1)
                    : targetClassName;

            int totalCovered = 0;
            int totalMissed = 0;
            boolean matchFound = false;

            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split(",");
                if (cols.length > Math.max(classIdx, Math.max(coveredIdx, missedIdx))) {
                    String cls = cols[classIdx].trim();
                    if (cls.equals(simpleName)
                            || cls.startsWith(simpleName + "$")
                            || cls.startsWith(simpleName + ".")
                            || cls.endsWith("/" + simpleName)
                            || cls.contains("/" + simpleName + "$")
                            || cls.contains("/" + simpleName + ".")) {
                        try {
                            int missed = Integer.parseInt(cols[missedIdx].trim());
                            int covered = Integer.parseInt(cols[coveredIdx].trim());
                            totalMissed += missed;
                            totalCovered += covered;
                            matchFound = true;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            if (matchFound) {
                int total = totalCovered + totalMissed;
                int pct = total > 0 ? (int) Math.round((totalCovered * 100.0) / total) : 0;
                log.info("JaCoCo coverage for {}: {}% (covered={}, missed={})", targetClassName, pct, totalCovered, totalMissed);
                return pct;
            }
        } catch (Exception e) {
            log.warn("Failed to parse JaCoCo CSV at {}: {}", jacocoCsv, e.getMessage());
        }

        return null;
    }
}
