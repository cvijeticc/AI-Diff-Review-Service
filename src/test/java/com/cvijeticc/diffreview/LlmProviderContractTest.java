package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * The llm path, end to end, against a stub that speaks the OpenAI Chat
 * Completions API. LlmProviderFailureTest covers what happens when the model
 * is unreachable; this covers what happens when it answers - which is the
 * half that cannot be exercised without either a live key or a stub, and so
 * is the half that tends to stay unverified until it breaks in production.
 *
 * <p>It also pins the things a live key would have hidden: that the request
 * carries the configured model, the token ceiling under the parameter name
 * reasoning models actually accept, and a schema the API is asked to enforce;
 * and that a reply truncated at that ceiling fails with a message naming the
 * cause instead of an unexplained JSON parse error.
 */
@TestPropertySource(properties = {
        "app.llm.api-key=stub-key",
        "app.llm.timeout-ms=5000",
        "app.llm.model=gpt-5-mini",
        "app.llm.max-tokens=16000"})
class LlmProviderContractTest extends BaseApiTest {

    private static HttpServer stub;
    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
        LAST_AUTH.set(null);
    }

    /** One choice, as the Chat Completions API shapes it. */
    private static String modelSays(String content, String finishReason) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "index", 0,
                        "finish_reason", finishReason,
                        "message", Map.of("role", "assistant", "content", content)))));
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
                {"findings": [
                  {"ruleId":"LLM-security","path":"llm-ok.js","line":1,"severity":"critical",
                   "category":"security","title":"eval on user input","evidence":"eval(userInput);"},
                  {"ruleId":"LLM-style","path":"llm-ok.js","line":2,"severity":"low",
                   "category":"style","title":"logs a secret","evidence":"console.log(secret);"}]}
                """, "stop"));

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
    void theRequestCarriesTheConfiguredModelCeilingAndSchema() throws Exception {
        RESPONSE.set(modelSays("{\"findings\": []}", "stop"));
        awaitTerminal(submitLlm("llm-request"));

        assertThat(LAST_AUTH.get()).isEqualTo("Bearer stub-key");

        JsonNode sent = MAPPER.readTree(LAST_REQUEST.get());
        assertThat(sent.path("model").asText()).isEqualTo("gpt-5-mini");
        // max_tokens is deprecated and rejected outright by reasoning models,
        // so sending the wrong name here is a 400 in production and nothing
        // locally - exactly the failure a stub exists to catch.
        assertThat(sent.path("max_completion_tokens").asInt()).isEqualTo(16_000);
        assertThat(sent.has("max_tokens")).isFalse();

        // The API is asked to enforce the shape rather than the parser hoping for it.
        assertThat(sent.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();

        JsonNode messages = sent.path("messages");
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).contains("untrusted DATA");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        // The diff must arrive fenced as data, never as instructions.
        assertThat(messages.get(1).path("content").asText()).startsWith("<diff>").endsWith("</diff>");
    }

    @Test
    void aTruncatedAnswerFailsWithAMessageThatNamesTheCause() throws Exception {
        // A reply cut off at the ceiling is a valid HTTP 200 carrying half a
        // JSON object. Reporting it as "no findings array" would send the
        // reader hunting the wrong bug.
        RESPONSE.set(modelSays("{\"findings\": [{\"ruleId\":\"LLM-security\",\"pat", "length"));

        JsonNode job = awaitTerminal(submitLlm("llm-truncated"));
        assertThat(job.path("status").asText()).isEqualTo("failed");
        assertThat(job.path("error").path("message").asText())
                .contains("token limit").contains("16000");
    }

    @Test
    void malformedEntriesAreDroppedRatherThanFailingTheWholeJob() throws Exception {
        RESPONSE.set(modelSays("""
                {"findings": [
                  {"ruleId":"LLM-security","path":"llm-partial.js","line":1,"severity":"nonsense",
                   "category":"invented","title":"real one","evidence":"eval(userInput);"},
                  {"ruleId":"LLM-broken","path":"","line":0,"title":"no path, no line"}]}
                """, "stop"));

        JsonNode job = awaitTerminal(submitLlm("llm-partial"));
        assertThat(job.path("status").asText()).isEqualTo("done");
        assertThat(job.path("findings")).hasSize(1);
        // unknown severity/category are clamped to the contract's vocabulary
        assertThat(job.path("findings").get(0).path("severity").asText()).isEqualTo("medium");
        assertThat(job.path("findings").get(0).path("category").asText()).isEqualTo("correctness");
    }

    @Test
    void aBareArrayInMarkdownFencesStillParses() throws Exception {
        // What a gateway that ignores response_format returns. Structured
        // Outputs is the guarantee, not the only thing the parser can read.
        RESPONSE.set(modelSays("""
                ```json
                [{"ruleId":"LLM-style","path":"llm-fenced.js","line":2,"severity":"low",
                  "category":"style","title":"stray log","evidence":"console.log(secret);"}]
                ```
                """, "stop"));

        JsonNode job = awaitTerminal(submitLlm("llm-fenced"));
        assertThat(job.path("status").asText()).isEqualTo("done");
        assertThat(job.path("findings")).hasSize(1);
        assertThat(job.path("findings").get(0).path("ruleId").asText()).isEqualTo("LLM-style");
    }
}
