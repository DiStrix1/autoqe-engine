package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AsyncConfig — enables Spring @Async and configures the dedicated thread pool
 * for long-running test generation jobs.
 *
 * <p>Pool sizing is externally configurable via properties:
 * <ul>
 *   <li>{@code qe.async.core-pool-size}: baseline threads (default: 2)</li>
 *   <li>{@code qe.async.max-pool-size}: maximum burst threads (default: 4)</li>
 *   <li>{@code qe.async.queue-capacity}: buffer queue size (default: 10)</li>
 * </ul>
 * Under extreme load, uses {@link ThreadPoolExecutor.CallerRunsPolicy} as backpressure
 * rather than discarding jobs.
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    @Value("${qe.async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${qe.async.max-pool-size:4}")
    private int maxPoolSize;

    @Value("${qe.async.queue-capacity:10}")
    private int queueCapacity;

    @Bean(name = "qeTaskExecutor")
    public Executor qeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("qe-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("QE async thread pool configured: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    @Bean
    public PathValidator pathValidator(
            WorkspacePathTranslator pathTranslator,
            @Value("${qe.sandbox.allowed-root:}") String allowedRoot) {
        return new PathValidator(pathTranslator, allowedRoot);
    }
}

