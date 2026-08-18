package com.cvijeticc.diffreview.api;

import com.cvijeticc.diffreview.api.error.ApiException;
import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.jobs.Job;
import com.cvijeticc.diffreview.jobs.ReviewService;
import com.cvijeticc.diffreview.jobs.SseEventRecord;
import com.cvijeticc.diffreview.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewController {

    private final ReviewService service;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final ExecutorService streamExecutor;

    public ReviewController(ReviewService service, RateLimiter rateLimiter, AppProperties props,
                            @Qualifier("streamExecutor") ExecutorService streamExecutor) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.streamExecutor = streamExecutor;
    }

    /**
     * The body is read as raw bytes on purpose: idempotency needs
     * byte-identical comparison of the original body, the 1 MiB guard must
     * fire before any parsing, and invalid JSON vs invalid diff need
     * distinct status codes - none of which a bound @RequestBody DTO gives.
     */
    @PostMapping("/v1/reviews")
    public ResponseEntity<Object> submit(HttpServletRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
            throws IOException {
        rateLimiter.acquireOrThrow();
        byte[] body = readBody(request);
        ReviewService.SubmitResult result = service.submit(body, idempotencyKey);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", result.jobId());
        out.put("status", result.status());
        return ResponseEntity.status(202).body(out);
    }

    @GetMapping("/v1/reviews/{jobId}")
    public Map<String, Object> get(@PathVariable String jobId) {
        return service.statusBody(service.getOrThrow(jobId));
    }

    /**
     * SSE stream. The job event log is replayed first and live events are
     * appended after it, atomically, so connecting to a finished job replays
     * every event identically to what a live subscriber saw.
     */
    @GetMapping(value = "/v1/reviews/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId, HttpServletResponse response) {
        Job job = service.getOrThrow(jobId);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        BlockingQueue<SseEventRecord> queue = new LinkedBlockingQueue<>();
        Consumer<SseEventRecord> listener = queue::add;
        job.subscribe(listener); // replays the log into the queue, then registers

        emitter.onCompletion(() -> job.unsubscribe(listener));
        emitter.onError(e -> job.unsubscribe(listener));

        streamExecutor.execute(() -> {
            try {
                while (true) {
                    SseEventRecord event = queue.poll(30, TimeUnit.MINUTES);
                    if (event == null) {
                        break; // safety valve for abandoned connections
                    }
                    emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
                    if (event.terminal()) {
                        break;
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e); // client went away; nothing to recover
            } finally {
                job.unsubscribe(listener);
            }
        });
        return emitter;
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        int max = props.maxPayloadBytes();
        long declared = request.getContentLengthLong();
        if (declared > max) {
            throw ApiException.payloadTooLarge(max);
        }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) {
            throw ApiException.payloadTooLarge(max);
        }
        return body;
    }
}
