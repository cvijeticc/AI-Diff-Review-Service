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
    /**
     * reasoningEffort is sent only when non-blank. A reasoning model spends its
     * reasoning phase before the first output token, which is what pushes a call
     * past the timeout; capping it is the lever that keeps latency bounded.
     * Blank disables the parameter entirely, because a non-reasoning model
     * behind LLM_BASE_URL rejects it with HTTP 400.
     */
    public record Llm(String apiKey, String baseUrl, String model, long timeoutMs, int maxTokens,
                      String reasoningEffort) {
    }
}
