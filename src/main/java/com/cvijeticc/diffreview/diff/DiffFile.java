package com.cvijeticc.diffreview.diff;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The parsed diff of a single file plus its raw text section. rawText is
 * what chunk sizing is measured on, and what the llm provider sends to the
 * model, so one file can never span two chunks.
 */
public record DiffFile(String path, List<Hunk> hunks, String rawText) {

    public int byteSize() {
        return rawText.getBytes(StandardCharsets.UTF_8).length;
    }
}
