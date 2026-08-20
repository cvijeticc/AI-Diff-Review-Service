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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Runs in its own context with a burst of 3 so the bucket can be emptied
 * quickly. Only POST /v1/reviews is limited; GETs never are.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth-token=test-token",
                "app.rate-limit-per-minute=3", "app.rate-limit-burst=3"})
class RateLimitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    private ResponseEntity<String> post(String marker) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer test-token");
        String diff = "--- a/" + marker + ".js\n+++ b/" + marker + ".js\n@@ -0,0 +1,1 @@\n+var x = 1;\n";
        String body = MAPPER.writeValueAsString(java.util.Map.of("diff", diff));
        return rest.exchange("http://localhost:" + port + "/v1/reviews",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void burstBeyondTheLimitGets429WithRetryAfterAndGetsAreNeverLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(post("rl" + i).getStatusCode().value()).isEqualTo(202);
        }
        ResponseEntity<String> limited = post("rl-over");
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
        long retryAfter = Long.parseLong(limited.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isGreaterThanOrEqualTo(1);
        // Proportional recovery: one token at 3/min is 20 s, and crucially it is
        // never the full 60 s window a fixed-window limiter would demand.
        assertThat(retryAfter).isLessThan(60);
        JsonNode envelope = MAPPER.readTree(limited.getBody());
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("rate_limited");
        assertThat(envelope.path("error").path("message").asText()).isNotBlank();

        // GETs are exempt: repeated polling while the POST bucket is empty still works
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer test-token");
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> get = rest.exchange(
                    "http://localhost:" + port + "/v1/reviews/does-not-exist",
                    HttpMethod.GET, new HttpEntity<>(headers), String.class);
            assertThat(get.getStatusCode().value()).isEqualTo(404); // not 429
        }
    }
}
