package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingIntegrationTest extends BaseApiTest {

    /** New-file section with one console.log marker at added line markerAt. */
    private static String fileSection(String path, int totalLines, int markerAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append("\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(path).append("\n");
        sb.append("@@ -0,0 +1,").append(totalLines).append(" @@\n");
        for (int i = 1; i <= totalLines; i++) {
            if (i == markerAt) {
                sb.append("+console.log(\"marker\");\n");
            } else {
                sb.append("+const filler").append(i)
                        .append(" = \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\";\n");
            }
        }
        return sb.toString();
    }

    @Test
    void largeDiffIsChunkedOnFileBoundariesWithIdenticalFindings() throws Exception {
        // ~56 KiB + ~56 KiB + ~2 KiB: greedy packing on file boundaries gives 2 chunks
        String diff = fileSection("chunk/a.js", 900, 3)
                + fileSection("chunk/b.js", 900, 3)
                + fileSection("chunk/c.js", 30, 3);

        JsonNode accepted = json(postReview(reviewBody(diff)));
        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("status").asText()).isEqualTo("done");

        JsonNode usage = job.path("usage");
        assertThat(usage.path("chunks").asInt()).isEqualTo(2);
        assertThat(usage.path("inputBytes").asLong())
                .isEqualTo(diff.getBytes(StandardCharsets.UTF_8).length);

        // exactly one finding per file, ordered by path, no duplicates and no losses
        List<String> ids = new ArrayList<>();
        job.path("findings").forEach(f -> ids.add(f.path("id").asText()));
        assertThat(ids).containsExactly(
                "MOCK-007:chunk/a.js:3", "MOCK-007:chunk/b.js:3", "MOCK-007:chunk/c.js:3");
    }

    @Test
    void fileOverChunkLimitBecomesItsOwnChunk() throws Exception {
        // ~68 KiB single file (over the 64 KiB chunk limit) + a small one
        String diff = fileSection("solo/big.js", 1100, 3) + fileSection("solo/tiny.js", 10, 3);
        JsonNode accepted = json(postReview(reviewBody(diff)));
        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("usage").path("chunks").asInt()).isEqualTo(2);
        assertThat(job.path("findings")).hasSize(2);
    }

    @Test
    void smallDiffIsASingleChunk() throws Exception {
        String diff = fileSection("small/one.js", 5, 2);
        JsonNode accepted = json(postReview(reviewBody(diff)));
        JsonNode job = awaitTerminal(accepted.path("jobId").asText());
        assertThat(job.path("usage").path("chunks").asInt()).isEqualTo(1);
    }
}
