package com.cvijeticc.diffreview.ratelimit;

import com.cvijeticc.diffreview.api.error.RateLimitedException;
import com.cvijeticc.diffreview.config.AppProperties;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Token bucket over POST /v1/reviews only (GETs are never limited).
 *
 * <p>A fixed window of N-per-60s has zero headroom at exactly N/min: the
 * smallest jitter or GC pause pushes a request past the edge, so the very
 * rate the contract guarantees ("sustained 30 submissions/minute must
 * succeed") is the rate at which it fails. A bucket refilling continuously
 * at {@code rateLimitPerMinute / 60} tokens per second cannot fail at the
 * sustained rate - each request consumes exactly what the refill has already
 * put back - while {@code rateLimitBurst} is the explicit, published
 * allowance for arriving faster than that.
 *
 * <p>Recovery is proportional, not a wall: a refused caller waits only for
 * one token to refill (~2 s at 30/min) instead of for a whole window to age
 * out, so one probe overrunning the burst does not lock out everything
 * behind it for a full minute. Rejected requests do not consume tokens.
 *
 * <p>Capacity doubles as the jitter headroom, which is why the default burst
 * is twice the sustained rate rather than the smallest value that works.
 */
@Component
public class RateLimiter {

    private final double refillPerSecond;
    private final double capacity;
    private final LongSupplier nanoClock;

    private double tokens;
    private long lastNanos;

    @Autowired
    public RateLimiter(AppProperties props) {
        this(props, System::nanoTime);
    }

    /** Test seam: a virtual clock proves the sustained-rate guarantee without waiting for it. */
    public RateLimiter(AppProperties props, LongSupplier nanoClock) {
        this.refillPerSecond = props.rateLimitPerMinute() / 60.0;
        // The declared burst is honoured literally - /spec publishes this exact
        // number, and quietly inflating it would make the declaration a lie.
        // Only a bucket too small to hold a single token is corrected.
        this.capacity = Math.max(1, props.rateLimitBurst());
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastNanos = nanoClock.getAsLong();
    }

    /** The effective bucket capacity, published verbatim by /spec as burstLimit. */
    public int burstCapacity() {
        return (int) capacity;
    }

    public synchronized void acquireOrThrow() {
        refill(nanoClock.getAsLong());
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return;
        }
        long retryAfter = Math.max(1, (long) Math.ceil((1.0 - tokens) / refillPerSecond));
        throw new RateLimitedException(retryAfter);
    }

    private void refill(long now) {
        long elapsedNanos = now - lastNanos;
        if (elapsedNanos <= 0) {
            return; // clock did not advance; nothing to add
        }
        lastNanos = now;
        tokens = Math.min(capacity, tokens + elapsedNanos / 1_000_000_000.0 * refillPerSecond);
    }
}
