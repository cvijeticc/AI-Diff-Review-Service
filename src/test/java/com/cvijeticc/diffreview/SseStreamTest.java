package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The processing delay makes the first connection a genuinely live stream;
 * the second connection is a replay of a finished job. Both must carry
 * identical events.
 */
@TestPropertySource(properties = "app.mock-delay-ms=300")
class SseStreamTest extends BaseApiTest {

    private static int count(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    void liveStreamAndReplayAreIdentical() throws Exception {
        String diff = diffOf(
                "--- a/sse.js",
                "+++ b/sse.js",
                "@@ -0,0 +1,2 @@",
                "+console.log(\"sse\");",
                "+// TODO sse");
        JsonNode accepted = json(postReview(reviewBody(diff)));
        String jobId = accepted.path("jobId").asText();

        String live = readStream(jobId);      // connects while the job is still running
        String replay = readStream(jobId);    // connects after the job finished

        assertThat(replay).isEqualTo(live);

        String normalized = live.replace("event: ", "event:").replace("data: ", "data:");
        assertThat(count(normalized, "event:status")).isGreaterThanOrEqualTo(3); // queued, running, done
        assertThat(count(normalized, "event:finding")).isEqualTo(2);
        assertThat(count(normalized, "event:done")).isEqualTo(1);

        // done is the last event; findings appear before it, statuses before findings
        int lastFinding = normalized.lastIndexOf("event:finding");
        int done = normalized.indexOf("event:done");
        int firstStatus = normalized.indexOf("event:status");
        assertThat(firstStatus).isLessThan(lastFinding);
        assertThat(lastFinding).isLessThan(done);
        assertThat(normalized).contains("\"status\":\"queued\"");
        assertThat(normalized).contains("\"status\":\"running\"");
        assertThat(normalized).contains("\"status\":\"done\"");
        assertThat(normalized).contains("\"total\":2");
    }

    @Test
    void streamOfUnknownJobIs404() throws Exception {
        ResponseEntity<String> r = getWithAuth("/v1/reviews/missing/stream");
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(json(r).path("error").path("code").asText()).isEqualTo("not_found");
    }
}
