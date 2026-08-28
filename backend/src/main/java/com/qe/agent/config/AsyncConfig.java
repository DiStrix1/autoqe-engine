package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig — enables Spring @Async and configures the dedicated thread pool
 * for long-running test generation jobs.
 *
 * <p>Improvement #J: decouples the HTTP request thread from the test generation
 * pipeline (which can run for 3-10+ minutes with Ollama retries), preventing
 * HTTP client timeouts on the async endpoint.
 *
 * <p>Pool sizing rationale:
 * <ul>
 *   <li>Core size 2: allows two concurrent generations without overloading Ollama.</li>
 *   <li>Max size 4: handles burst without starvation.</li>
 *   <li>Queue 10: absorbs short bursts while the pool is fully loaded.</li>
 * </ul>
 * Adjust via {@code qe.async.core-pool-size}, {@code qe.async.max-pool-size},
 * and {@code qe.async.queue-capacity} in application.properties (future work).
 */
@Slf4j
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "qeTaskExecutor")
    public Executor qeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("qe-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("QE async thread pool configured: core=2, max=4, queue=10");
        return executor;
    }
}
