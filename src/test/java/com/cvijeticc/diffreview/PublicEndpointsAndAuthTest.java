package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Deliberately boots with default limits so /spec is asserted against the
 * exact numbers the contract publishes. Only the token is overridden.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth-token=test-token")
class PublicEndpointsAndAuthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void healthIsPublicAndWellFormed() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url("/health"), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(r.getBody());
        assertThat(body.path("status").asText()).isEqualTo("ok");
        assertThat(body.path("version").asText()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(body.path("uptimeSeconds").isNumber()).isTrue();
        assertThat(body.path("uptimeSeconds").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void specIsPublicAndDeclaresTheContractLimits() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url("/spec"), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(r.getBody());
        assertThat(body.path("specVersion").asText()).isEqualTo("1.0");
        assertThat(body.path("providers").get(0).asText()).isEqualTo("mock");
        assertThat(body.path("providers").get(1).asText()).isEqualTo("llm");
        JsonNode limits = body.path("limits");
        assertThat(limits.path("maxPayloadBytes").asInt()).isEqualTo(1_048_576);
        assertThat(limits.path("chunkBytes").asInt()).isEqualTo(65_536);
        assertThat(limits.path("maxConcurrentJobs").asInt()).isEqualTo(4);
        assertThat(limits.path("rateLimitPerMinute").asInt()).isEqualTo(30);
    }

    @Test
    void allV1RoutesRequireTheBearerToken() throws Exception {
        // no token
        ResponseEntity<String> noToken = rest.getForEntity(url("/v1/reviews/x"), String.class);
        assertThat(noToken.getStatusCode().value()).isEqualTo(401);
        JsonNode envelope = MAPPER.readTree(noToken.getBody());
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("unauthorized");
        assertThat(envelope.path("error").path("message").asText()).isNotBlank();

        // wrong token
        HttpHeaders wrong = new HttpHeaders();
        wrong.set("Authorization", "Bearer wrong-token");
        assertThat(rest.exchange(url("/v1/reviews/x"), HttpMethod.GET,
                new HttpEntity<>(wrong), String.class).getStatusCode().value()).isEqualTo(401);

        // POST and the stream route are protected the same way
        assertThat(rest.postForEntity(url("/v1/reviews"), "{}", String.class)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(rest.getForEntity(url("/v1/reviews/x/stream"), String.class)
                .getStatusCode().value()).isEqualTo(401);

        // with the right token the same route is no longer 401
        HttpHeaders right = new HttpHeaders();
        right.set("Authorization", "Bearer test-token");
        assertThat(rest.exchange(url("/v1/reviews/x"), HttpMethod.GET,
                new HttpEntity<>(right), String.class).getStatusCode().value()).isEqualTo(404);
    }
}
