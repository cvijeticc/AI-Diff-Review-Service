package com.cvijeticc.diffreview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of truth for the limits declared in GET /spec.
 * The rate limiter, chunker, executor and payload guard all read from here,
 * so the declaration can never drift from actual behavior.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String version,
        String authToken,
        int maxPayloadBytes,
        int chunkBytes,
        int maxConcurrentJobs,
        int rateLimitPerMinute,
        int rateLimitBurst,
        int maxConcurrentLlmJobs,
        long jobTtlSeconds,
        long keyTtlSeconds,
        int maxRetainedJobs,
        long mockDelayMs,
        Llm llm
) {
    public record Llm(String apiKey, String baseUrl, String model, long timeoutMs, int maxTokens) {
    }
}
