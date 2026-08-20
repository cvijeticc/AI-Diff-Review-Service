package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.provider.LlmReviewProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The llm base URL points at a closed local port, so every llm job hits an
 * unreachable model. The contract requires graceful degradation: a failed
 * job with a clear error, never a crash.
 */
@TestPropertySource(properties = {
        "app.llm.api-key=test-key-for-failure-path",
        "app.llm.base-url=http://127.0.0.1:1",
        "app.llm.timeout-ms=2000"})
class LlmProviderFailureTest extends BaseApiTest {

    private static String llmBody(String marker) throws Exception {
        String diff = diffOf(
                "--- a/" + marker + ".js",
                "+++ b/" + marker + ".js",
                "@@ -0,0 +1,1 @@",
                "+eval(x);");
        return MAPPER.writeValueAsString(java.util.Map.of(
                "diff", diff,
                "options", java.util.Map.of("provider", "llm")));
    }

    @Test
    void unreachableModelFailsTheJobGracefully() throws Exception {
        JsonNode accepted = json(postReview(llmBody("llm-fail")));
        assertThat(accepted.path("status").asText()).isEqualTo("queued");

        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("error").path("message").asText()).isNotBlank();
        assertThat(job.has("findings")).isFalse();

        // the service itself is alive and mock jobs still work after the failure
        assertThat(rest.getForEntity(url("/health"), String.class).getStatusCode().value()).isEqualTo(200);
        JsonNode mockJob = awaitTerminal(json(postReview(reviewBody(diffOf(
                "--- a/after.js", "+++ b/after.js", "@@ -0,0 +1,1 @@", "+// TODO after"))))
                .path("jobId").asText());
        assertThat(mockJob.path("status").asText()).isEqualTo("done");
    }

    @Test
    void missingApiKeyFailsFastWithAClearMessage() {
        AppProperties props = new AppProperties("1.0.3", "t", 1_048_576, 65_536, 4, 30, 60, 4,
                86_400, 604_800, 10_000, 0,
                new AppProperties.Llm("", "http://127.0.0.1:1", "gpt-5-mini", 1000, 16_000));
        LlmReviewProvider provider = new LlmReviewProvider(props, new ObjectMapper());
        try {
            provider.review(List.of());
            throw new AssertionError("expected IllegalStateException");
        } catch (Exception e) {
            assertThat(e).isInstanceOf(IllegalStateException.class);
            assertThat(e.getMessage()).contains("not configured");
        }
    }
}
