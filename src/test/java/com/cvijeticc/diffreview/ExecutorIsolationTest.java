package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A mock job must not be made slow by llm jobs it has nothing to do with.
 *
 * <p>On a single shared pool this is a real failure: every llm call holds its
 * thread for up to the model timeout, so a handful of them queue ahead of a
 * mock job that would have finished instantly, and the mock job misses the
 * latency budget without anything being wrong with it. Two pools make the
 * slow provider structurally unable to starve the fast one.
 */
@TestPropertySource(properties = {
        "app.llm.api-key=stub-key",
        "app.llm.timeout-ms=20000",
        "app.max-concurrent-jobs=4",
        "app.max-concurrent-llm-jobs=4"})
class ExecutorIsolationTest extends BaseApiTest {

    private static final long MODEL_STALL_MS = 1200;
    private static final int LLM_JOBS = 12; // 3 full rounds of the llm pool

    private static HttpServer stub;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(MODEL_STALL_MS); // a model that is merely slow, not broken
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}],\"stop_reason\":\"end_turn\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.llm.base-url", () -> "http://127.0.0.1:" + stub.getAddress().getPort());
    }

    @Test
    void aBacklogOfSlowLlmJobsDoesNotDelayAMockJob() throws Exception {
        List<String> llmJobs = new ArrayList<>();
        for (int i = 0; i < LLM_JOBS; i++) {
            llmJobs.add(json(postReview(MAPPER.writeValueAsString(Map.of(
                    "diff", diffOf("--- a/slow" + i + ".js", "+++ b/slow" + i + ".js",
                            "@@ -0,0 +1,1 @@", "+eval(x);"),
                    "options", Map.of("provider", "llm"))))).path("jobId").asText());
        }

        long start = System.nanoTime();
        JsonNode mockJob = awaitTerminal(json(postReview(reviewBody(diffOf(
                "--- a/fast.js", "+++ b/fast.js", "@@ -0,0 +1,1 @@", "+// TODO fast"))))
                .path("jobId").asText());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(mockJob.path("status").asText()).isEqualTo("done");
        // Sharing one pool of 4 would put this job behind 12 stalled calls -
        // three rounds of MODEL_STALL_MS. It has to be nowhere near that.
        assertThat(elapsedMs)
                .withFailMessage("mock job waited %d ms behind llm jobs", elapsedMs)
                .isLessThan(MODEL_STALL_MS);

        for (String jobId : llmJobs) {
            assertThat(awaitTerminal(jobId).path("status").asText()).isEqualTo("done");
        }
    }
}
