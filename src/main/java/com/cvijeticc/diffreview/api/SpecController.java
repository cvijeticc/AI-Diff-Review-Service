package com.cvijeticc.diffreview.api;

import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.ratelimit.RateLimiter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Machine-readable self-declaration. Every limit is read from the same
 * AppProperties instance the enforcing components use - and burstLimit
 * straight off the limiter object itself - so the declaration cannot drift
 * from actual behavior.
 */
@RestController
public class SpecController {

    private final AppProperties props;
    private final RateLimiter rateLimiter;

    public SpecController(AppProperties props, RateLimiter rateLimiter) {
        this.props = props;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/spec")
    public Map<String, Object> spec() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxPayloadBytes", props.maxPayloadBytes());
        limits.put("chunkBytes", props.chunkBytes());
        limits.put("maxConcurrentJobs", props.maxConcurrentJobs());
        limits.put("rateLimitPerMinute", props.rateLimitPerMinute());
        // The contract talks about "your declared burst", so declare it.
        limits.put("burstLimit", rateLimiter.burstCapacity());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("specVersion", "1.0");
        out.put("providers", List.of("mock", "llm"));
        out.put("limits", limits);
        return out;
    }
}
