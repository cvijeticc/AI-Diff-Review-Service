package com.cvijeticc.diffreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Shared plumbing for the black-box integration tests. The rate limit is
 * raised here so unrelated tests never trip it; RateLimitTest runs in its
 * own context with a small limit to test the real behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.auth-token=test-token",
                "app.rate-limit-per-minute=100000", "app.rate-limit-burst=100000"})
public abstract class BaseApiTest {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected static final String TOKEN = "test-token";

    @Autowired
    protected TestRestTemplate rest;

    @LocalServerPort
    protected int port;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected HttpHeaders authJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + TOKEN);
        return headers;
    }

    protected ResponseEntity<String> postReview(String body) {
        return postReview(body, null);
    }

    protected ResponseEntity<String> postReview(String body, String idempotencyKey) {
        HttpHeaders headers = authJsonHeaders();
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(url("/v1/reviews"), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> getWithAuth(String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authJsonHeaders()), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    protected JsonNode getJob(String jobId) throws Exception {
        return json(getWithAuth("/v1/reviews/" + jobId));
    }

    /** Polls until done/failed; the 30 s ceiling is the latency budget from the contract. */
    protected JsonNode awaitTerminal(String jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = getJob(jobId);
            String status = job.path("status").asText();
            if (status.equals("done") || status.equals("failed")) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + jobId + " did not finish within 30 s");
    }

    /** Reads the whole SSE stream until the server closes it. */
    protected String readStream(String jobId) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/v1/reviews/" + jobId + "/stream")))
                .header("Authorization", "Bearer " + TOKEN)
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError("stream returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    protected static String diffOf(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    protected static String reviewBody(String diff) throws Exception {
        return MAPPER.writeValueAsString(java.util.Map.of("diff", diff));
    }
}
