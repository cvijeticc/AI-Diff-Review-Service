package com.cvijeticc.diffreview;

import static org.assertj.core.api.Assertions.assertThat;

import com.cvijeticc.diffreview.diff.DiffChunker;
import com.cvijeticc.diffreview.diff.DiffFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffChunkerTest {

    private static final int LIMIT = 65536;

    private static DiffFile file(String path, int bytes) {
        return new DiffFile(path, List.of(), "x".repeat(bytes));
    }

    @Test
    void smallFilesShareOneChunk() {
        List<List<DiffFile>> chunks = DiffChunker.chunk(
                List.of(file("a", 10_000), file("b", 20_000), file("c", 30_000)), LIMIT);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(3);
    }

    @Test
    void filesArePackedGreedilyOnFileBoundaries() {
        List<List<DiffFile>> chunks = DiffChunker.chunk(
                List.of(file("a", 60_000), file("b", 60_000), file("c", 2_000)), LIMIT);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).extracting(DiffFile::path).containsExactly("a");
        assertThat(chunks.get(1)).extracting(DiffFile::path).containsExactly("b", "c");
    }

    @Test
    void oversizedFileGetsItsOwnChunk() {
        List<List<DiffFile>> chunks = DiffChunker.chunk(
                List.of(file("big", 70_000), file("small", 10_000)), LIMIT);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).extracting(DiffFile::path).containsExactly("big");
        assertThat(chunks.get(1)).extracting(DiffFile::path).containsExactly("small");
    }

    @Test
    void singleOversizedFileIsOneChunk() {
        assertThat(DiffChunker.chunk(List.of(file("big", 70_000)), LIMIT)).hasSize(1);
    }

    @Test
    void orderIsPreservedAcrossChunks() {
        List<List<DiffFile>> chunks = DiffChunker.chunk(
                List.of(file("a", 10_000), file("b", 60_000), file("c", 10_000)), LIMIT);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).get(0).path()).isEqualTo("a");
        assertThat(chunks.get(1).get(0).path()).isEqualTo("b");
        assertThat(chunks.get(2).get(0).path()).isEqualTo("c");
    }
}
