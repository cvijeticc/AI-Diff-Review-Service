package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The job store is bounded, so a job id can stop resolving. Every read of one
 * has to survive that - including the idempotent-replay path, which used to
 * dereference the job a stored key pointed at without checking whether it was
 * still there. Unbounded maps hid the bug; bounding them is what makes it
 * reachable, so it is pinned here.
 *
 * <p>Keys and cached findings deliberately outlive jobs, and this test relies
 * on exactly that asymmetry: the job TTL is squeezed to a second while the key
 * TTL stays long, which reproduces in seconds the state the defaults reach
 * after a day.
 */
@TestPropertySource(properties = {
        "app.job-ttl-seconds=1",
        "app.key-ttl-seconds=3600"})
class JobRetentionTest extends BaseApiTest {

    private static final long PAST_JOB_TTL_MS = 1400;

    private String bodyFor(String marker) throws Exception {
        return MAPPER.writeValueAsString(Map.of("diff", diffOf(
                "--- a/" + marker + ".js", "+++ b/" + marker + ".js",
                "@@ -0,0 +1,1 @@", "+// TODO " + marker)));
    }

    private void waitForJobToAgeOut(String jobId) throws Exception {
        Thread.sleep(PAST_JOB_TTL_MS);
        assertThat(getWithAuth("/v1/reviews/" + jobId).getStatusCode().value())
                .withFailMessage("job %s outlived its TTL", jobId)
                .isEqualTo(404);
    }

    @Test
    void anIdempotentReplayAfterTheJobIsGoneRedoesTheWorkInsteadOf500() throws Exception {
        String key = "retention-replay";
        String body = bodyFor("retention");
        String firstJobId = json(postReview(body, key)).path("jobId").asText();
        assertThat(awaitTerminal(firstJobId).path("status").asText()).isEqualTo("done");

        waitForJobToAgeOut(firstJobId);

        // Same key, same body, but the job it pointed at is gone. The only
        // honest answer is to redo the work - not to NPE into a 500.
        ResponseEntity<String> replay = postReview(body, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(202);
        JsonNode replayed = json(replay);
        assertThat(replayed.path("jobId").asText()).isNotEqualTo(firstJobId);
        assertThat(awaitTerminal(replayed.path("jobId").asText()).path("status").asText())
                .isEqualTo("done");
    }

    @Test
    void aConflictingBodyIsStillRejectedAfterTheOriginalJobAgedOut() throws Exception {
        String key = "retention-conflict";
        String firstJobId = json(postReview(bodyFor("conflict-a"), key)).path("jobId").asText();
        assertThat(awaitTerminal(firstJobId).path("status").asText()).isEqualTo("done");

        waitForJobToAgeOut(firstJobId);

        // The key outlives the job on purpose: reusing it for a different body
        // has to keep failing, or idempotency degrades into "sometimes".
        ResponseEntity<String> conflict = postReview(bodyFor("conflict-b"), key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(json(conflict).path("error").path("code").asText())
                .isEqualTo("idempotency_conflict");
    }

    @Test
    void anExpiredJobIdReadsAs404WithTheEnvelopeOnBothReadRoutes() throws Exception {
        String jobId = json(postReview(bodyFor("gone"))).path("jobId").asText();
        assertThat(awaitTerminal(jobId).path("status").asText()).isEqualTo("done");

        waitForJobToAgeOut(jobId);

        ResponseEntity<String> gone = getWithAuth("/v1/reviews/" + jobId);
        assertThat(gone.getStatusCode().value()).isEqualTo(404);
        assertThat(json(gone).path("error").path("code").asText()).isEqualTo("not_found");
        assertThat(getWithAuth("/v1/reviews/" + jobId + "/stream").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void cachedFindingsOutliveTheJobThatProducedThem() throws Exception {
        String body = bodyFor("cache-outlives");
        String firstJobId = json(postReview(body)).path("jobId").asText();
        JsonNode first = awaitTerminal(firstJobId);
        assertThat(first.path("usage").path("cacheHit").asBoolean()).isFalse();

        waitForJobToAgeOut(firstJobId);

        // The findings are keyed by content, not by job, so the work is not
        // repeated just because the job record aged out.
        JsonNode second = awaitTerminal(json(postReview(body)).path("jobId").asText());
        assertThat(second.path("usage").path("cacheHit").asBoolean()).isTrue();
        assertThat(second.path("findings")).isEqualTo(first.path("findings"));
    }
}
