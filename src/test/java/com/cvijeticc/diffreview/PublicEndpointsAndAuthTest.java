package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        // The contract talks about "your declared burst", so it has to be declared.
        assertThat(limits.path("burstLimit").asInt()).isEqualTo(60);
        assertThat(limits.path("burstLimit").asInt())
                .isGreaterThanOrEqualTo(limits.path("rateLimitPerMinute").asInt());
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

    @Test
    void theBearerSchemeIsMatchedCaseInsensitively() {
        // RFC 7235 makes the scheme name case-insensitive, so "bearer x" is a
        // valid credential; rejecting it would be our bug, not the client's.
        for (String scheme : new String[]{"Bearer", "bearer", "BEARER", "BeArEr"}) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", scheme + " test-token");
            assertThat(rest.exchange(url("/v1/reviews/x"), HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getStatusCode().value())
                    .withFailMessage("scheme %s was rejected", scheme)
                    .isEqualTo(404); // past auth, into the handler
        }

        // The token itself stays case-sensitive and is still compared in full.
        HttpHeaders wrongCase = new HttpHeaders();
        wrongCase.set("Authorization", "bearer TEST-TOKEN");
        assertThat(rest.exchange(url("/v1/reviews/x"), HttpMethod.GET,
                new HttpEntity<>(wrongCase), String.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void springsOwnErrorPathReturnsTheEnvelopeNotTheDefaultBody() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url("/error"), String.class);
        assertThat(r.getStatusCode().value()).isGreaterThanOrEqualTo(400);
        JsonNode body = MAPPER.readTree(r.getBody());
        assertThat(body.path("error").path("code").asText()).isNotBlank();
        assertThat(body.path("error").path("message").asText()).isNotBlank();
        // none of Spring's default keys leak through
        assertThat(body.has("timestamp")).isFalse();
        assertThat(body.has("status")).isFalse();
        assertThat(body.has("path")).isFalse();
    }

    @Test
    void containerLevelRejectionsAlsoCarryTheEnvelope() throws Exception {
        // Tomcat refuses an encoded slash in the path before any servlet runs
        // and would otherwise answer with its own HTML page. The contract asks
        // for the envelope on every non-2xx, including the ones we never see.
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url("/%2Fv1/reviews/x"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(r.body()).doesNotContain("<html").doesNotContain("<!DOCTYPE");
        JsonNode body = MAPPER.readTree(r.body());
        assertThat(body.path("error").path("code").asText()).isNotBlank();
        assertThat(body.path("error").path("message").asText()).isNotBlank();
    }

    @Test
    void unknownRoutesAndWrongMethodsUseTheEnvelope() throws Exception {
        ResponseEntity<String> missing = rest.getForEntity(url("/nope"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(MAPPER.readTree(missing.getBody()).path("error").path("code").asText())
                .isEqualTo("not_found");

        ResponseEntity<String> wrongMethod = rest.exchange(url("/health"), HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(wrongMethod.getStatusCode().value()).isEqualTo(405);
        assertThat(MAPPER.readTree(wrongMethod.getBody()).path("error").path("code").asText())
                .isEqualTo("method_not_allowed");
    }
}
