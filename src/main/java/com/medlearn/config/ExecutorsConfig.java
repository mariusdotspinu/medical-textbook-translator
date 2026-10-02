package com.medlearn.config;

import com.medlearn.platform.config.IngestionProperties;
import com.medlearn.platform.config.TranslationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The two executors this application needs, and why they are not the same one.
 *
 * <p><b>Virtual threads for jobs and API calls.</b> A translation job spends nearly all its
 * time blocked on HTTP to Anthropic. Virtual threads make thousands of such waits free,
 * which is exactly what they are for.
 *
 * <p><b>A bounded platform pool for PDF extraction.</b> Page parsing and rasterising are
 * CPU- and memory-bound, and each worker holds its own {@code PDDocument}. Running that on
 * an unbounded virtual-thread executor would launch a thousand concurrent page parses and
 * exhaust the heap - virtual threads remove the cost of *waiting*, not the cost of *working*.
 *
 * <p>Worth stating plainly: neither executor raises throughput against the Anthropic API.
 * That ceiling is the account's rate limit, which is what
 * {@link TranslationProperties#maxInFlight()} exists to respect.
 */
@Configuration
public class ExecutorsConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorsConfig.class);

    /** Job orchestration and outbound API calls: blocking waits, so virtual threads. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService jobExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** PDF extraction: real CPU work, so a bounded pool of platform threads. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService extractionExecutor(IngestionProperties properties) {
        int parallelism = properties.resolvedParallelism();
        log.info("PDF extraction pool: {} platform threads ({} cores available)",
                parallelism, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(parallelism, namedThreadFactory("pdf-extract-"));
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
