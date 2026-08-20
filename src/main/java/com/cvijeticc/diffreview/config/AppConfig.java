package com.cvijeticc.diffreview.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.catalina.core.StandardHost;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Makes Tomcat's own pre-servlet rejections come back as the error envelope. */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonErrorReportValve() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }

    /**
     * Fixed pool sized by the declared maxConcurrentJobs, with an unbounded
     * queue: at least 4 jobs run concurrently and a queued 5th never fails.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService jobExecutor(AppProperties props) {
        return Executors.newFixedThreadPool(props.maxConcurrentJobs(), namedFactory("job-"));
    }

    /**
     * llm jobs run on their own pool. Sharing one pool means a handful of
     * llm calls, each holding a thread for up to the model timeout, queue up
     * ahead of mock jobs that would otherwise finish instantly - which is how
     * a mock job blows the latency budget without anything being wrong with
     * it. Separate pools make the slow provider unable to starve the fast one.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService llmExecutor(AppProperties props) {
        return Executors.newFixedThreadPool(props.maxConcurrentLlmJobs(), namedFactory("llm-"));
    }

    /** Pumps SSE events to connected clients; streams are short-lived. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService streamExecutor() {
        return Executors.newCachedThreadPool(namedFactory("sse-"));
    }

    private static java.util.concurrent.ThreadFactory namedFactory(String prefix) {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
