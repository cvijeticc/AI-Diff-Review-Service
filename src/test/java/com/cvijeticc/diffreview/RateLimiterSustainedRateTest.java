package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cvijeticc.diffreview.api.error.RateLimitedException;
import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.ratelimit.RateLimiter;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The contract's guarantee is not "429 eventually arrives" - it is
 * "sustained 30 submissions/minute must succeed". Those are different
 * claims, and only the first one is easy to test. A test that proves the
 * limit engages proves nothing about the limit not engaging when it must
 * not, which is where the earlier fixed-window implementation failed.
 *
 * <p>A virtual clock lets this run the real production numbers over ten
 * simulated minutes in microseconds; RateLimitTest then measures the same
 * property wall-clock through the full HTTP stack.
 */
class RateLimiterSustainedRateTest {

    private final AtomicLong nanos = new AtomicLong();

    private RateLimiter limiter(int perMinute, int burst) {
        nanos.set(0);
        return new RateLimiter(props(perMinute, burst), nanos::get);
    }

    private static AppProperties props(int perMinute, int burst) {
        return new AppProperties("1.0.3", "t", 1_048_576, 65_536, 4, perMinute, burst, 4,
                86_400, 604_800, 10_000, 0,
                new AppProperties.Llm("", "http://127.0.0.1:1", "gpt-5-mini", 1000, 16_000));
    }

    private void advanceMillis(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }

    @Test
    void sustainedContractRateNeverGets429EvenAfterTheBurstIsSpent() {
        RateLimiter limiter = limiter(30, 60);

        // Spend the whole declared burst up front - the worst possible start.
        for (int i = 0; i < 60; i++) {
            limiter.acquireOrThrow();
        }

        // Then hold exactly the guaranteed rate: one submission every 2 s for
        // ten minutes. Not one of them may be refused.
        for (int i = 0; i < 300; i++) {
            advanceMillis(2000);
            int request = i;
            assertThat(catching(limiter))
                    .withFailMessage("sustained 30/min was refused at request %d", request)
                    .isNull();
        }
    }

    @Test
    void burstBeyondTheDeclaredCapacityIsRefusedAndRecoversInSecondsNotAMinute() {
        RateLimiter limiter = limiter(30, 60);

        for (int i = 0; i < 60; i++) {
            limiter.acquireOrThrow(); // the declared burst is honoured in full
        }
        RateLimitedException refused = catching(limiter);
        assertThat(refused).isNotNull();

        // Recovery is proportional: one token at 30/min is 2 s, not a whole
        // window. A single probe overrunning the burst must not lock out
        // everything behind it for the next minute.
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 3L);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("rate_limited");

        advanceMillis(refused.retryAfterSeconds() * 1000);
        assertThat(catching(limiter)).isNull();
    }

    @Test
    void refusedRequestsDoNotConsumeCapacity() {
        RateLimiter limiter = limiter(60, 2);

        limiter.acquireOrThrow();
        limiter.acquireOrThrow();
        for (int i = 0; i < 50; i++) {
            assertThat(catching(limiter)).isNotNull(); // hammering while empty
        }

        // 2 s of refill at 60/min is exactly 2 tokens; the rejected attempts
        // above must not have eaten into them.
        advanceMillis(2000);
        assertThat(catching(limiter)).isNull();
        assertThat(catching(limiter)).isNull();
        assertThat(catching(limiter)).isNotNull();
    }

    @Test
    void theDeclaredBurstIsWhatSpecPublishesAndWhatTheBucketHolds() {
        // /spec reads burstLimit straight off this object, so the published
        // number and the enforced one cannot drift apart.
        assertThat(limiter(30, 60).burstCapacity()).isEqualTo(60);
        assertThat(limiter(30, 5).burstCapacity()).isEqualTo(5);
    }

    @Test
    void retryAfterIsAlwaysAtLeastOneSecond() {
        RateLimiter limiter = limiter(3000, 1); // 50/s: one token refills in 20 ms
        limiter.acquireOrThrow();
        RateLimitedException refused = catching(limiter);
        assertThat(refused).isNotNull();
        assertThat(refused.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tokensAreCappedAtCapacitySoIdleTimeDoesNotBankUnlimitedCredit() {
        RateLimiter limiter = limiter(60, 5);
        advanceMillis(600_000); // ten idle minutes
        for (int i = 0; i < 5; i++) {
            assertThat(catching(limiter)).isNull();
        }
        assertThat(catching(limiter)).isNotNull(); // capped at 5, not 600
    }

    @Test
    void rejectionCarriesTheContractEnvelopeFields() {
        RateLimiter limiter = limiter(1, 1);
        limiter.acquireOrThrow();
        assertThatThrownBy(limiter::acquireOrThrow)
                .isInstanceOf(RateLimitedException.class)
                .hasMessageContaining("retry after");
    }

    private static RateLimitedException catching(RateLimiter limiter) {
        try {
            limiter.acquireOrThrow();
            return null;
        } catch (RateLimitedException e) {
            return e;
        }
    }
}
