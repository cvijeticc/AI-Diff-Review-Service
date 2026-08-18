package com.cvijeticc.diffreview.jobs;

import com.cvijeticc.diffreview.diff.DiffFile;
import com.cvijeticc.diffreview.model.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A review job plus its append-only SSE event log. The log is the single
 * source of truth for streaming: live subscribers and later replays both
 * read the same records, which is what makes replays byte-identical.
 */
public final class Job {

    private final String id;
    private final String provider;
    private final int maxFindings;
    private final long inputBytes;
    private final int chunkCount;
    private final String cacheKey;
    private final List<List<DiffFile>> chunks;

    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile List<Finding> findings = List.of();
    private volatile boolean cacheHit;
    private volatile String errorMessage;

    private final List<SseEventRecord> events = new ArrayList<>();
    private final List<Consumer<SseEventRecord>> listeners = new ArrayList<>();

    public Job(String id, String provider, int maxFindings, long inputBytes,
               int chunkCount, String cacheKey, List<List<DiffFile>> chunks) {
        this.id = id;
        this.provider = provider;
        this.maxFindings = maxFindings;
        this.inputBytes = inputBytes;
        this.chunkCount = chunkCount;
        this.cacheKey = cacheKey;
        this.chunks = chunks;
    }

    public String id() {
        return id;
    }

    public String provider() {
        return provider;
    }

    public int maxFindings() {
        return maxFindings;
    }

    public long inputBytes() {
        return inputBytes;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public String cacheKey() {
        return cacheKey;
    }

    public List<List<DiffFile>> chunks() {
        return chunks;
    }

    public JobStatus status() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public List<Finding> findings() {
        return findings;
    }

    public void setFindings(List<Finding> findings) {
        this.findings = findings;
    }

    public boolean cacheHit() {
        return cacheHit;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /** Appends to the log and forwards to live subscribers, in one atomic step. */
    public synchronized void emit(SseEventRecord event) {
        events.add(event);
        for (Consumer<SseEventRecord> l : List.copyOf(listeners)) {
            l.accept(event);
        }
    }

    /**
     * Atomically replays the existing log into the consumer and registers it
     * for future events, so a subscriber can never miss or reorder events.
     */
    public synchronized void subscribe(Consumer<SseEventRecord> consumer) {
        for (SseEventRecord e : events) {
            consumer.accept(e);
        }
        listeners.add(consumer);
    }

    public synchronized void unsubscribe(Consumer<SseEventRecord> consumer) {
        listeners.remove(consumer);
    }
}
