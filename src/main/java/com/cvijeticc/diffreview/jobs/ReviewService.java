package com.cvijeticc.diffreview.jobs;

import com.cvijeticc.diffreview.api.error.ApiException;
import com.cvijeticc.diffreview.config.AppProperties;
import com.cvijeticc.diffreview.diff.DiffChunker;
import com.cvijeticc.diffreview.diff.DiffFile;
import com.cvijeticc.diffreview.diff.DiffParseException;
import com.cvijeticc.diffreview.diff.UnifiedDiffParser;
import com.cvijeticc.diffreview.model.Finding;
import com.cvijeticc.diffreview.provider.ProviderRegistry;
import com.cvijeticc.diffreview.provider.ReviewProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Owns the whole review pipeline: request validation, idempotency, the
 * result cache, chunking, async execution, dedup + ordering + truncation,
 * and the SSE event log. Providers only ever see one chunk at a time.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<Finding>> resultCache = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();
    private final Object idempotencyLock = new Object();

    private final AppProperties props;
    private final ProviderRegistry providers;
    private final ExecutorService jobExecutor;
    private final ObjectMapper mapper;

    private record IdempotencyEntry(String bodySha256, String jobId) {
    }

    public record SubmitResult(String jobId, String status) {
    }

    public ReviewService(AppProperties props, ProviderRegistry providers,
                         @Qualifier("jobExecutor") ExecutorService jobExecutor, ObjectMapper mapper) {
        this.props = props;
        this.providers = providers;
        this.jobExecutor = jobExecutor;
        this.mapper = mapper;
    }

    // ---------- submission ----------

    public SubmitResult submit(byte[] rawBody, String idempotencyKey) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw ApiException.invalidJson();
        }
        if (root == null || !root.isObject()) {
            throw ApiException.invalidDiff("request body must be a JSON object with a diff field");
        }
        JsonNode diffNode = root.get("diff");
        if (diffNode == null || diffNode.isNull()) {
            throw ApiException.invalidDiff("diff is required");
        }
        if (!diffNode.isTextual() || diffNode.asText().isEmpty()) {
            throw ApiException.invalidDiff("diff must be a non-empty string");
        }
        String diff = diffNode.asText();

        String provider = "mock";
        int maxFindings = 100;
        JsonNode options = root.get("options");
        if (options != null && options.isObject()) {
            JsonNode p = options.get("provider");
            if (p != null && !p.isNull()) {
                if (!p.isTextual() || !(p.asText().equals("mock") || p.asText().equals("llm"))) {
                    throw ApiException.invalidOptions("options.provider must be one of: mock, llm");
                }
                provider = p.asText();
            }
            JsonNode mf = options.get("maxFindings");
            if (mf != null && !mf.isNull()) {
                if (!mf.isIntegralNumber() || !mf.canConvertToInt() || mf.asInt() < 0) {
                    throw ApiException.invalidOptions("options.maxFindings must be a non-negative integer");
                }
                maxFindings = mf.asInt();
            }
        }
        // Unknown fields anywhere in the body are ignored by design.

        List<DiffFile> files;
        try {
            files = UnifiedDiffParser.parse(diff);
        } catch (DiffParseException e) {
            throw ApiException.invalidDiff("diff is not parseable as a unified diff: " + e.getMessage());
        }

        String bodySha = sha256(rawBody);
        String cacheKey = sha256((provider + "\n" + maxFindings + "\n" + diff).getBytes(StandardCharsets.UTF_8));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            synchronized (idempotencyLock) {
                IdempotencyEntry existing = idempotency.get(idempotencyKey);
                if (existing != null) {
                    if (!existing.bodySha256().equals(bodySha)) {
                        throw ApiException.idempotencyConflict();
                    }
                    Job job = jobs.get(existing.jobId());
                    return new SubmitResult(job.id(), job.status().json());
                }
                Job job = createAndEnqueue(diff, files, provider, maxFindings, cacheKey);
                idempotency.put(idempotencyKey, new IdempotencyEntry(bodySha, job.id()));
                return new SubmitResult(job.id(), JobStatus.QUEUED.json());
            }
        }
        Job job = createAndEnqueue(diff, files, provider, maxFindings, cacheKey);
        return new SubmitResult(job.id(), JobStatus.QUEUED.json());
    }

    private Job createAndEnqueue(String diff, List<DiffFile> files, String provider,
                                 int maxFindings, String cacheKey) {
        List<List<DiffFile>> chunks = DiffChunker.chunk(files, props.chunkBytes());
        long inputBytes = diff.getBytes(StandardCharsets.UTF_8).length;
        Job job = new Job(UUID.randomUUID().toString(), provider, maxFindings,
                inputBytes, chunks.size(), cacheKey, chunks);
        jobs.put(job.id(), job);
        emitStatus(job);
        jobExecutor.submit(() -> run(job));
        return job;
    }

    // ---------- execution ----------

    private void run(Job job) {
        job.setStatus(JobStatus.RUNNING);
        emitStatus(job);
        try {
            if (props.mockDelayMs() > 0) {
                Thread.sleep(props.mockDelayMs());
            }
            List<Finding> full = resultCache.get(job.cacheKey());
            boolean hit = full != null;
            if (!hit) {
                ReviewProvider provider = providers.get(job.provider());
                LinkedHashMap<String, Finding> byId = new LinkedHashMap<>();
                for (List<DiffFile> chunk : job.chunks()) {
                    for (Finding f : provider.review(chunk)) {
                        byId.putIfAbsent(f.id(), f); // dedup by id across and within chunks
                    }
                }
                List<Finding> sorted = new ArrayList<>(byId.values());
                sorted.sort(Finding.ORDER);
                full = List.copyOf(sorted);
                resultCache.putIfAbsent(job.cacheKey(), full);
            }
            job.setCacheHit(hit);
            List<Finding> visible = full.size() > job.maxFindings()
                    ? List.copyOf(full.subList(0, job.maxFindings()))
                    : full;
            job.setFindings(visible);
            job.setStatus(JobStatus.DONE);
            for (Finding f : visible) {
                job.emit(new SseEventRecord("finding", toJson(f), false));
            }
            emitStatus(job);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("total", visible.size());
            done.put("usage", usageMap(job));
            job.emit(new SseEventRecord("done", toJson(done), true));
        } catch (Exception e) {
            log.warn("Job {} failed: {}", job.id(), e.toString());
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            job.setStatus(JobStatus.FAILED);
            emitStatus(job);
        }
    }

    private void emitStatus(Job job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.id());
        data.put("status", job.status().json());
        job.emit(new SseEventRecord("status", toJson(data), job.status() == JobStatus.FAILED));
    }

    // ---------- reads ----------

    public Job getOrThrow(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw ApiException.notFound("job " + jobId);
        }
        return job;
    }

    public Map<String, Object> statusBody(Job job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id());
        out.put("status", job.status().json());
        if (job.status() == JobStatus.DONE) {
            out.put("findings", job.findings());
        }
        out.put("usage", usageMap(job));
        if (job.status() == JobStatus.FAILED) {
            out.put("error", Map.of("code", "provider_error", "message", job.errorMessage()));
        }
        return out;
    }

    private Map<String, Object> usageMap(Job job) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputBytes", job.inputBytes());
        usage.put("chunks", job.chunkCount());
        usage.put("cacheHit", job.cacheHit());
        return usage;
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
