package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The llm path, end to end, against a stub that speaks the Anthropic Messages
 * API. LlmProviderFailureTest covers what happens when the model is
 * unreachable; this covers what happens when it answers - which is the half
 * that cannot be exercised without either a live key or a stub, and so is the
 * half that tends to stay unverified until it breaks in production.
 *
 * <p>It also pins the two things a real key would have hidden: that the
 * configured max_tokens is what actually goes on the wire, and that a
 * response truncated by that ceiling fails with a message naming the cause
 * instead of an unexplained JSON parse error.
 */
@TestPropertySource(properties = {
        "app.llm.api-key=stub-key",
        "app.llm.timeout-ms=5000",
        "app.llm.model=claude-sonnet-5",
        "app.llm.max-tokens=16000"})
class LlmProviderContractTest extends BaseApiTest {

    private static HttpServer stub;
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/messages", exchange -> {
            LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = RESPONSE.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
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

    @BeforeEach
    void resetStub() {
        LAST_REQUEST.set(null);
    }

    private static String modelSays(String text, String stopReason) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "content", java.util.List.of(Map.of("type", "text", "text", text)),
                "stop_reason", stopReason));
    }

    private String submitLlm(String marker) throws Exception {
        String diff = diffOf(
                "--- a/" + marker + ".js",
                "+++ b/" + marker + ".js",
                "@@ -0,0 +1,2 @@",
                "+eval(userInput);",
                "+console.log(secret);");
        String body = MAPPER.writeValueAsString(Map.of(
                "diff", diff, "options", Map.of("provider", "llm")));
        return json(postReview(body)).path("jobId").asText();
    }

    @Test
    void aModelAnswerBecomesFindingsThroughTheSamePipelineAsMock() throws Exception {
        RESPONSE.set(modelSays("""
                [{"ruleId":"LLM-security","path":"llm-ok.js","line":1,"severity":"critical",
                  "category":"security","title":"eval on user input","evidence":"eval(userInput);"},
                 {"ruleId":"LLM-style","path":"llm-ok.js","line":2,"severity":"low",
                  "category":"style","title":"logs a secret","evidence":"console.log(secret);"}]
                """, "end_turn"));

        JsonNode job = awaitTerminal(submitLlm("llm-ok"));
        assertThat(job.path("status").asText()).isEqualTo("done");
        assertThat(job.path("findings")).hasSize(2);
        assertThat(job.path("findings").get(0).path("ruleId").asText()).isEqualTo("LLM-security");
        assertThat(job.path("findings").get(0).path("line").asInt()).isEqualTo(1);
        assertThat(job.path("findings").get(1).path("line").asInt()).isEqualTo(2);
        // usage is filled by the pipeline, identically to a mock job
        assertThat(job.path("usage").path("chunks").asInt()).isEqualTo(1);
        assertThat(job.path("usage").path("findingsTotal").asInt()).isEqualTo(2);
    }

    @Test
    void theConfiguredMaxTokensAndModelAreWhatActuallyGoOnTheWire() throws Exception {
        RESPONSE.set(modelSays("[]", "end_turn"));
        awaitTerminal(submitLlm("llm-request"));

        JsonNode sent = MAPPER.readTree(LAST_REQUEST.get());
        assertThat(sent.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(16_000);
        // The diff must arrive fenced as data, never as instructions.
        String content = sent.path("messages").get(0).path("content").asText();
        assertThat(content).startsWith("<diff>").endsWith("</diff>");
        assertThat(sent.path("system").asText()).contains("untrusted DATA");
    }

    @Test
    void aTruncatedAnswerFailsWithAMessageThatNamesTheCause() throws Exception {
        // A response cut off at the ceiling is a valid HTTP 200 carrying half a
        // JSON array. Reporting it as "not a JSON array" would send the reader
        // hunting the wrong bug.
        RESPONSE.set(modelSays("[{\"ruleId\":\"LLM-security\",\"path\":\"x.js\",\"lin", "max_tokens"));

        JsonNode job = awaitTerminal(submitLlm("llm-truncated"));
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("error").path("message").asText())
                .contains("max_tokens").contains("16000");
    }

    @Test
    void malformedEntriesAreDroppedRatherThanFailingTheWholeJob() throws Exception {
        RESPONSE.set(modelSays("""
                [{"ruleId":"LLM-security","path":"llm-partial.js","line":1,"severity":"nonsense",
                  "category":"invented","title":"real one","evidence":"eval(userInput);"},
                 {"ruleId":"LLM-broken","path":"","line":0,"title":"no path, no line"}]
                """, "end_turn"));

        JsonNode job = awaitTerminal(submitLlm("llm-partial"));
        assertThat(job.path("status").asText()).isEqualTo("done");
        assertThat(job.path("findings")).hasSize(1);
        // unknown severity/category are clamped to the contract's vocabulary
        assertThat(job.path("findings").get(0).path("severity").asText()).isEqualTo("medium");
        assertThat(job.path("findings").get(0).path("category").asText()).isEqualTo("correctness");
    }

    @Test
    void aModelReplyWrappedInMarkdownFencesStillParses() throws Exception {
        RESPONSE.set(modelSays("""
                ```json
                [{"ruleId":"LLM-style","path":"llm-fenced.js","line":2,"severity":"low",
                  "category":"style","title":"stray log","evidence":"console.log(secret);"}]
                ```
                """, "end_turn"));

        JsonNode job = awaitTerminal(submitLlm("llm-fenced"));
        assertThat(job.path("status").asText()).isEqualTo("done");
        assertThat(job.path("findings")).hasSize(1);
        assertThat(job.path("findings").get(0).path("ruleId").asText()).isEqualTo("LLM-style");
    }
}
