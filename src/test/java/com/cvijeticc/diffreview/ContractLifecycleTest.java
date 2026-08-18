package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ContractLifecycleTest extends BaseApiTest {

    private static String sampleDiff() {
        return diffOf(
                "--- a/src/app.js",
                "+++ b/src/app.js",
                "@@ -1,3 +1,7 @@",
                " const a = 1;",
                "+console.log(\"boot\");",
                "+const q = \"SELECT * FROM t WHERE id=\" + id;",
                " const b = 2;",
                "+// TODO refactor",
                "+if (x == null) { eval(code); }",
                " const c = 3;");
    }

    @Test
    void happyPathSubmitPollFindings() throws Exception {
        String diff = sampleDiff();
        ResponseEntity<String> submit = postReview(reviewBody(diff));
        assertThat(submit.getStatusCode().value()).isEqualTo(202);
        JsonNode accepted = json(submit);
        assertThat(accepted.path("jobId").asText()).isNotBlank();
        assertThat(accepted.path("status").asText()).isEqualTo("queued");

        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("status").asText()).isEqualTo("done");

        JsonNode findings = job.path("findings");
        assertThat(findings.isArray()).isTrue();
        List<String> ruleIds = new ArrayList<>();
        findings.forEach(f -> ruleIds.add(f.path("ruleId").asText()));
        assertThat(ruleIds).containsExactly("MOCK-007", "MOCK-003", "MOCK-008", "MOCK-001", "MOCK-005");

        List<Integer> lines = new ArrayList<>();
        findings.forEach(f -> lines.add(f.path("line").asInt()));
        assertThat(lines).containsExactly(2, 3, 5, 6, 6);

        JsonNode first = findings.get(0);
        assertThat(first.path("id").asText()).isEqualTo("MOCK-007:src/app.js:2");
        assertThat(first.path("path").asText()).isEqualTo("src/app.js");
        assertThat(first.path("severity").asText()).isEqualTo("low");
        assertThat(first.path("category").asText()).isEqualTo("style");
        assertThat(first.path("evidence").asText()).isEqualTo("console.log(\"boot\");");

        JsonNode usage = job.path("usage");
        assertThat(usage.path("inputBytes").asLong())
                .isEqualTo(diff.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(usage.path("chunks").asInt()).isEqualTo(1);
        assertThat(usage.path("cacheHit").asBoolean()).isFalse();
    }

    @Test
    void maxFindingsTruncatesButUsageReflectsFullScan() throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "diff", sampleDiff(),
                "options", java.util.Map.of("maxFindings", 2)));
        JsonNode accepted = json(postReview(body));
        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("findings")).hasSize(2);
        assertThat(job.path("findings").get(0).path("ruleId").asText()).isEqualTo("MOCK-007");
        assertThat(job.path("findings").get(1).path("ruleId").asText()).isEqualTo("MOCK-003");
        assertThat(job.path("usage").path("chunks").asInt()).isEqualTo(1);
    }

    @Test
    void findingsAreOrderedByPathAcrossFiles() throws Exception {
        String diff = diffOf(
                "--- a/zed.js",
                "+++ b/zed.js",
                "@@ -0,0 +1,1 @@",
                "+console.log(\"z\");",
                "--- a/alpha.js",
                "+++ b/alpha.js",
                "@@ -0,0 +1,1 @@",
                "+console.log(\"a\");");
        JsonNode accepted = json(postReview(reviewBody(diff)));
        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("findings").get(0).path("path").asText()).isEqualTo("alpha.js");
        assertThat(job.path("findings").get(1).path("path").asText()).isEqualTo("zed.js");
    }

    @Test
    void invalidJsonIs400() throws Exception {
        ResponseEntity<String> r = postReview("{not json at all");
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(json(r).path("error").path("code").asText()).isEqualTo("invalid_json");
        assertThat(json(r).path("error").path("message").asText()).isNotBlank();
    }

    @Test
    void missingEmptyOrUnparseableDiffIs422() throws Exception {
        assertThat(postReview("{}").getStatusCode().value()).isEqualTo(422);
        ResponseEntity<String> empty = postReview("{\"diff\":\"\"}");
        assertThat(empty.getStatusCode().value()).isEqualTo(422);
        assertThat(json(empty).path("error").path("code").asText()).isEqualTo("invalid_diff");
        assertThat(postReview("{\"diff\":123}").getStatusCode().value()).isEqualTo(422);
        assertThat(postReview("{\"diff\":\"just some text\"}").getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void oversizedPayloadIs413() throws Exception {
        String big = "{\"diff\":\"" + "a".repeat(1_048_600) + "\"}";
        ResponseEntity<String> r = postReview(big);
        assertThat(r.getStatusCode().value()).isEqualTo(413);
        assertThat(json(r).path("error").path("code").asText()).isEqualTo("payload_too_large");
    }

    @Test
    void unknownJobIs404() throws Exception {
        ResponseEntity<String> r = getWithAuth("/v1/reviews/no-such-job");
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(json(r).path("error").path("code").asText()).isEqualTo("not_found");
    }

    @Test
    void unknownBodyFieldsAreIgnored() throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "diff", sampleDiff(),
                "surprise", true,
                "options", java.util.Map.of("provider", "mock", "maxFindings", 50, "extra", 1)));
        ResponseEntity<String> r = postReview(body);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void unknownProviderIsRejected() throws Exception {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "diff", sampleDiff(),
                "options", java.util.Map.of("provider", "gpt")));
        ResponseEntity<String> r = postReview(body);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
    }
}
