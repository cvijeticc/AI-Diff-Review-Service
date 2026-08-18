package com.cvijeticc.diffreview.jobs;

/**
 * One SSE event as it will appear on the wire (event name + single-line
 * JSON data). terminal marks the last event of a stream (done, or a failed
 * status), after which the connection is closed.
 */
public record SseEventRecord(String event, String data, boolean terminal) {
}
