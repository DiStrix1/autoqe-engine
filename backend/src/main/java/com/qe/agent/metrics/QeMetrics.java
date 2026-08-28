package com.qe.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * QeMetrics — Micrometer-based counters for the test generation pipeline.
 *
 * <p>Improvement #F: provides structured, queryable metrics via Spring Actuator
 * (/actuator/metrics) instead of requiring log analysis to assess pipeline health.
 *
 * <p>Exposed metrics:
 * <ul>
 *   <li>{@code qe.tests.generated} — total generation requests received.</li>
 *   <li>{@code qe.tests.passed} — runs that reached PASSED status.</li>
 *   <li>{@code qe.tests.failed} — runs that ended in FAILED_AFTER_HEALING or error.</li>
 *   <li>{@code qe.tests.retry.total} — cumulative retry attempts across all runs.</li>
 * </ul>
 */
@Slf4j
@Component
public class QeMetrics {

    private final Counter generatedCounter;
    private final Counter passedCounter;
    private final Counter failedCounter;
    private final Counter retryCounter;

    public QeMetrics(MeterRegistry registry) {
        this.generatedCounter = Counter.builder("qe.tests.generated")
                .description("Total test generation requests received")
                .register(registry);
        this.passedCounter = Counter.builder("qe.tests.passed")
                .description("Test runs that reached PASSED status")
                .register(registry);
        this.failedCounter = Counter.builder("qe.tests.failed")
                .description("Test runs that ended in failure or error")
                .register(registry);
        this.retryCounter = Counter.builder("qe.tests.retry.total")
                .description("Cumulative self-healing retry attempts across all runs")
                .register(registry);
        log.info("QeMetrics registered: qe.tests.generated / passed / failed / retry.total");
    }

    /**
     * Records the outcome of a single pipeline run.
     *
     * @param status   the status string from {@link com.qe.agent.model.TestGenerationResponse}
     * @param attempts total attempts (including retries) used
     */
    public void recordGeneration(String status, int attempts) {
        generatedCounter.increment();
        if ("PASSED".equals(status)) {
            passedCounter.increment();
        } else {
            failedCounter.increment();
        }
        // Retry attempts = total attempts minus the first attempt
        int retries = Math.max(0, attempts - 1);
        if (retries > 0) {
            retryCounter.increment(retries);
        }
        log.debug("[QeMetrics] recorded: status={} attempts={} retries={}", status, attempts, retries);
    }
}
