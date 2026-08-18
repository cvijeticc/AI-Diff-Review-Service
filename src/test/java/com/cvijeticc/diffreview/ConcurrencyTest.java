package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * With a 400 ms simulated processing time and 4 workers, five quick
 * submissions exercise the full pool plus the queue: the fifth job must be
 * accepted, wait its turn and still finish - never fail.
 */
@TestPropertySource(properties = "app.mock-delay-ms=400")
class ConcurrencyTest extends BaseApiTest {

    @Test
    void fiveConcurrentJobsAllSucceed() throws Exception {
        List<String> jobIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String diff = diffOf(
                    "--- a/con" + i + ".js",
                    "+++ b/con" + i + ".js",
                    "@@ -0,0 +1,1 @@",
                    "+console.log(\"con" + i + "\");");
            JsonNode accepted = json(postReview(reviewBody(diff)));
            assertThat(accepted.path("jobId").asText()).isNotBlank();
            jobIds.add(accepted.path("jobId").asText());
        }
        for (String jobId : jobIds) {
            JsonNode job = awaitTerminal(jobId);
            assertThat(job.path("status").asText()).isEqualTo("done");
            assertThat(job.path("findings")).hasSize(1);
        }
    }
}
