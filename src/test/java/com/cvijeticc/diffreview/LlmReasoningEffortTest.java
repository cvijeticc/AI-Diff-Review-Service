package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.provider.LlmReviewProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * reasoning_effort is conditional, and both branches matter in production:
 * sent, it is the only thing bounding the reasoning phase that caused the
 * "request timed out" outage; sent to a non-reasoning model behind a custom
 * LLM_BASE_URL, it is an HTTP 400. A stub is the only way to see which one
 * actually left the process, so both are pinned here.
 */
class LlmReasoningEffortTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer stub;
    private static final AtomicReference<String> LAST_REQUEST = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/chat/completions", exchange -> {
            LAST_REQUEST.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\","
                    + "\"message\":{\"content\":\"{\\\"findings\\\": []}\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
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

    private static String requestBodyWithEffort(String effort) throws Exception {
        AppProperties props = new AppProperties("1.1.0", "t", 1_048_576, 65_536, 4, 30, 60, 4,
                86_400, 604_800, 10_000, 0,
                new AppProperties.Llm("stub-key", "http://127.0.0.1:" + stub.getAddress().getPort(),
                        "gpt-5-mini", 5_000, 16_000, effort));
        LAST_REQUEST.set(null);
        new LlmReviewProvider(props, MAPPER).review(List.of());
        return LAST_REQUEST.get();
    }

    @Test
    void aConfiguredEffortReachesTheApi() throws Exception {
        assertThat(MAPPER.readTree(requestBodyWithEffort("low"))
                .path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test
    void ablankEffortIsOmittedEntirelyRatherThanSentEmpty() throws Exception {
        // An empty string is a 400 from the API, so "disabled" has to mean absent.
        assertThat(MAPPER.readTree(requestBodyWithEffort("")).has("reasoning_effort")).isFalse();
        assertThat(MAPPER.readTree(requestBodyWithEffort(null)).has("reasoning_effort")).isFalse();
    }
}
