package com.cvijeticc.diffreview.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy packing of file sections into chunks of at most chunkBytes,
 * splitting only on file boundaries. A single file section larger than
 * chunkBytes becomes its own chunk.
 */
public final class DiffChunker {

    private DiffChunker() {
    }

    public static List<List<DiffFile>> chunk(List<DiffFile> files, int chunkBytes) {
        List<List<DiffFile>> chunks = new ArrayList<>();
        List<DiffFile> current = new ArrayList<>();
        int currentSize = 0;
        for (DiffFile f : files) {
            int size = f.byteSize();
            if (size > chunkBytes) {
                if (!current.isEmpty()) {
                    chunks.add(current);
                    current = new ArrayList<>();
                    currentSize = 0;
                }
                chunks.add(List.of(f));
                continue;
            }
            if (!current.isEmpty() && currentSize + size > chunkBytes) {
                chunks.add(current);
                current = new ArrayList<>();
                currentSize = 0;
            }
            current.add(f);
            currentSize += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
