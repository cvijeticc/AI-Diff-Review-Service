package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The same guarantee as RateLimiterSustainedRateTest, but measured against a
 * real server over real time, because a virtual clock can only prove the
 * arithmetic - not that the limiter is wired into the request path with that
 * arithmetic intact.
 *
 * <p>It reproduces exactly the experiment that exposed the original
 * fixed-window limiter: hammer until the limit engages, then hold the
 * sustained rate and require every single request to be accepted. A fixed
 * window fails the second half by construction - once it has refused, it
 * keeps refusing until its oldest entries age out a minute later - so this
 * test discriminates between the two implementations rather than merely
 * passing under both.
 *
 * <p>The rate is scaled up to 300/min (one every 200 ms) so the whole thing
 * takes seconds rather than the minute the contract's own 30/min would need.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth-token=test-token",
                "app.rate-limit-per-minute=300", "app.rate-limit-burst=300"})
class RateLimitWallClockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PERIOD_MS = 200;      // 300/min
    private static final int SUSTAINED_REQUESTS = 20;
    private static final int DRAIN_CEILING = 3000;  // safety valve, never reached in practice

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private int post(String marker) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer test-token");
        String diff = "--- a/" + marker + ".js\n+++ b/" + marker + ".js\n@@ -0,0 +1,1 @@\n+var x = 1;\n";
        String body = MAPPER.writeValueAsString(java.util.Map.of("diff", diff));
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + "/v1/reviews",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return r.getStatusCode().value();
    }

    @Test
    void sustainedRateIsAcceptedOverTheWireImmediatelyAfterTheLimitEngages() throws Exception {
        int drained = 0;
        while (drained < DRAIN_CEILING && post("wc-drain" + drained) == 202) {
            drained++;
        }
        assertThat(drained)
                .withFailMessage("the limiter never engaged after %d requests", drained)
                .isLessThan(DRAIN_CEILING);

        // The bucket is empty and a request was just refused. From here on the
        // caller behaves perfectly: exactly the declared sustained rate.
        List<Integer> refusedAt = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < SUSTAINED_REQUESTS; i++) {
            Thread.sleep(PERIOD_MS);
            if (post("wc-sustained" + i) != 202) {
                refusedAt.add(i);
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Sanity check on the measurement itself: pacing must not have drifted
        // faster than the declared rate, or the assertion below proves nothing.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(SUSTAINED_REQUESTS * PERIOD_MS);
        assertThat(refusedAt)
                .withFailMessage("sustained rate was refused at request(s) %s, %d ms after the limit engaged",
                        refusedAt, elapsedMs)
                .isEmpty();
    }
}
