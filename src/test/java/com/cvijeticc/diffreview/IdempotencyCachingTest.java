package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class IdempotencyCachingTest extends BaseApiTest {

    private static String uniqueDiff(String marker) {
        return diffOf(
                "--- a/" + marker + ".js",
                "+++ b/" + marker + ".js",
                "@@ -0,0 +1,2 @@",
                "+console.log(\"" + marker + "\");",
                "+// TODO " + marker);
    }

    @Test
    void sameKeyAndSameBodyReturnTheSameJobId() throws Exception {
        String body = reviewBody(uniqueDiff("idem-a"));
        JsonNode first = json(postReview(body, "key-1"));
        awaitTerminal(first.path("jobId").asText());

        ResponseEntity<String> replayResponse = postReview(body, "key-1");
        assertThat(replayResponse.getStatusCode().value()).isEqualTo(202);
        JsonNode replay = json(replayResponse);
        assertThat(replay.path("jobId").asText()).isEqualTo(first.path("jobId").asText());
    }

    @Test
    void sameKeyWithDifferentBodyIs409() throws Exception {
        postReview(reviewBody(uniqueDiff("idem-b")), "key-2");
        ResponseEntity<String> conflict = postReview(reviewBody(uniqueDiff("idem-b-other")), "key-2");
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(json(conflict).path("error").path("code").asText()).isEqualTo("idempotency_conflict");
    }

    @Test
    void byteIdenticalResubmissionHitsTheCacheWithIdenticalFindings() throws Exception {
        String body = reviewBody(uniqueDiff("cache-a"));

        JsonNode first = json(postReview(body));
        JsonNode firstJob = awaitTerminal(first.path("jobId").asText());
        assertThat(firstJob.path("usage").path("cacheHit").asBoolean()).isFalse();

        JsonNode second = json(postReview(body));
        assertThat(second.path("jobId").asText()).isNotEqualTo(first.path("jobId").asText());
        JsonNode secondJob = awaitTerminal(second.path("jobId").asText());
        assertThat(secondJob.path("usage").path("cacheHit").asBoolean()).isTrue();
        assertThat(secondJob.path("findings")).isEqualTo(firstJob.path("findings"));

        // any idempotency key on a byte-identical {diff, options} still hits the cache
        JsonNode third = json(postReview(body, "cache-key-x"));
        JsonNode thirdJob = awaitTerminal(third.path("jobId").asText());
        assertThat(thirdJob.path("usage").path("cacheHit").asBoolean()).isTrue();
    }

    @Test
    void differentOptionsMeanDifferentCacheEntries() throws Exception {
        String diff = uniqueDiff("cache-opts");
        JsonNode first = json(postReview(reviewBody(diff)));
        awaitTerminal(first.path("jobId").asText());

        String withOptions = MAPPER.writeValueAsString(java.util.Map.of(
                "diff", diff,
                "options", java.util.Map.of("maxFindings", 1)));
        JsonNode second = json(postReview(withOptions));
        JsonNode secondJob = awaitTerminal(second.path("jobId").asText());
        assertThat(secondJob.path("usage").path("cacheHit").asBoolean()).isFalse();
        assertThat(secondJob.path("findings")).hasSize(1);
    }
}
