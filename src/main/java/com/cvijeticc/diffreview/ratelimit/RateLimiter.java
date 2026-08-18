package com.cvijeticc.diffreview.ratelimit;

import com.cvijeticc.diffreview.api.error.RateLimitedException;
import com.cvijeticc.diffreview.config.AppProperties;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.stereotype.Component;

/**
 * Sliding-window limiter over the last 60 seconds, applied to
 * POST /v1/reviews only (GETs are never limited). Sustained
 * rateLimitPerMinute submissions succeed; anything beyond gets 429 with a
 * Retry-After hint. Rejected requests do not consume the window.
 */
@Component
public class RateLimiter {

    private static final long WINDOW_NANOS = 60_000_000_000L;

    private final int limit;
    private final Deque<Long> accepted = new ArrayDeque<>();

    public RateLimiter(AppProperties props) {
        this.limit = props.rateLimitPerMinute();
    }

    public synchronized void acquireOrThrow() {
        long now = System.nanoTime();
        while (!accepted.isEmpty() && now - accepted.peekFirst() >= WINDOW_NANOS) {
            accepted.pollFirst();
        }
        if (accepted.size() < limit) {
            accepted.addLast(now);
            return;
        }
        long oldest = accepted.peekFirst();
        long nanosUntilFree = WINDOW_NANOS - (now - oldest);
        long retryAfter = Math.max(1, (nanosUntilFree + 999_999_999L) / 1_000_000_000L);
        throw new RateLimitedException(retryAfter);
    }
}
