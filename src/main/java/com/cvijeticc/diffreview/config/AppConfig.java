package com.cvijeticc.diffreview.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * Fixed pool sized by the declared maxConcurrentJobs, with an unbounded
     * queue: at least 4 jobs run concurrently and a queued 5th never fails.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService jobExecutor(AppProperties props) {
        return Executors.newFixedThreadPool(props.maxConcurrentJobs());
    }

    /** Pumps SSE events to connected clients; streams are short-lived. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService streamExecutor() {
        return Executors.newCachedThreadPool();
    }
}
