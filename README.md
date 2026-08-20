# AI Diff Review Service

Async code-review service: clients POST a unified diff, the service reviews it
asynchronously through a provider (`mock` or `llm`) and returns structured,
ordered, deduplicated findings — with chunking, caching, idempotency, SSE
streaming with replay, rate limiting and bounded concurrency.

Built with Java 17 / Spring Boot 3. No database: state is in-memory by design
(see `SUBMISSION.md` for the reasoning and what production hardening would look
like).

## API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/health` | public | `{status, version, uptimeSeconds}` |
| GET | `/spec` | public | machine-readable limits self-declaration |
| POST | `/v1/reviews` | bearer | submit a diff, returns `202 {jobId, status}` |
| GET | `/v1/reviews/{jobId}` | bearer | job status + findings + usage |
| GET | `/v1/reviews/{jobId}/stream` | bearer | SSE: `status`, `finding`, `done`; full replay for finished jobs |

Request body:

```json
{
  "diff": "<unified diff>",
  "options": { "provider": "mock", "maxFindings": 100 }
}
```

Error responses always use the envelope
`{"error": {"code": "...", "message": "..."}}` with codes:
`unauthorized`, `payload_too_large`, `invalid_json`, `invalid_diff`,
`idempotency_conflict`, `not_found`, `rate_limited`, `internal`.

- `Idempotency-Key` header: same key + byte-identical body → same `jobId`;
  same key + different body → `409`.
- Byte-identical `{diff, options}` resubmitted (any key or none) → served from
  the result cache, `usage.cacheHit: true`, findings identical.

## Run locally

```bash
AUTH_TOKEN=my-secret ./mvnw spring-boot:run
```

The service listens on `:8080` (override with `PORT`). Quick smoke test:

```bash
curl -s localhost:8080/health
TOKEN=my-secret
curl -s -X POST localhost:8080/v1/reviews \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"diff":"--- a/x.js\n+++ b/x.js\n@@ -0,0 +1,1 @@\n+console.log(1);\n"}'
curl -s localhost:8080/v1/reviews/<jobId> -H "Authorization: Bearer $TOKEN"
curl -N localhost:8080/v1/reviews/<jobId>/stream -H "Authorization: Bearer $TOKEN"
```

## Tests

```bash
./mvnw test
```

90 tests. Unit tests cover every mock rule (including the tricky negatives:
`=== null`, multi-line empty `catch`, removed lines that look like `---` file
headers) plus the parser — strict while the `@@` counts run, tolerant of the
headers those counts cannot vouch for — and the chunker. Black-box integration
tests boot the real server and verify the full contract: lifecycle, auth, error
taxonomy, chunk counts with identical findings, cache hits, idempotency
replay/conflict, byte-identical SSE replay, 4+1 concurrency, job eviction, and
the llm path against a stub that speaks the Messages API.

Two tests are aimed at the rate limiter's *sustained* guarantee rather than at
its ceiling, because those are different claims and only the ceiling is easy to
assert: `RateLimiterSustainedRateTest` runs the contract's own 30/min for ten
simulated minutes on a virtual clock, and `RateLimitWallClockTest` reproduces
the same experiment over real time through the full HTTP stack — hammer until
the limit engages, then hold the sustained rate and require zero refusals.

### Verifying a running instance

`postman/` holds a conformance suite that asserts the same contract against a
deployed service — 31 requests, 85 assertions, no import required:

```bash
npx newman run postman/AI-Diff-Review-Service.postman_collection.json --env-var baseUrl=http://localhost:8080 --env-var token=YOUR_TOKEN
```

It covers the behaviours that a single request cannot show: chunk boundaries on
an 87 KiB diff, cache miss then hit with deep-equal findings, idempotency replay
and conflict, byte-identical SSE replay, the full error taxonomy including the
1 MiB guard, parser tolerance for imperfect `@@` headers, and llm degradation.
One step is strict on purpose: it fails unless the `llm` provider is genuinely
configured on the server. See [postman/README.md](postman/README.md).

## Configuration

| Env var | Default | Purpose |
|---------|---------|---------|
| `AUTH_TOKEN` | `dev-only-token` | bearer token for all `/v1/*` routes — always set in production |
| `PORT` | `8080` | HTTP port |
| `OPENAI_API_KEY` | *(empty)* | enables the `llm` provider; without it llm jobs fail gracefully |
| `LLM_MODEL` | `gpt-5-mini` | model id for the llm provider |
| `LLM_BASE_URL` | `https://api.openai.com` | LLM API base URL; any OpenAI-compatible endpoint works |
| `LLM_TIMEOUT_MS` | `20000` | per-request LLM timeout |
| `LLM_MAX_TOKENS` | `16000` | output ceiling for a model reply; a chunk's findings array can be long |
| `MOCK_DELAY_MS` | `0` | artificial per-job delay; used by tests to observe concurrency/live SSE |

The limits published by `/spec` (1 MiB payload, 64 KiB chunks, 4 concurrent
jobs, 30 submissions/min sustained with a burst of 60) live in
`application.properties` and are read by the enforcing components from the same
`AppProperties` bean, so the declaration cannot drift from behavior —
`burstLimit` is read straight off the limiter object itself.

Rate limiting is a token bucket: `rateLimitPerMinute` is the sustained rate that
is guaranteed to succeed, `rateLimitBurst` the declared allowance for arriving
faster than that. A refused caller waits only for one token to refill (~2 s),
not for a whole window to age out.

Jobs, cached results and idempotency keys are bounded by both size
(`app.max-retained-jobs`) and age (`app.job-ttl-seconds` for jobs,
`app.key-ttl-seconds` for keys and cached findings). Keys are retained longer
than the jobs they refer to, deliberately: an idempotency key must outlive its
job or a replay with a *different* body would quietly start new work instead of
answering 409.

## The llm provider

`options.provider: "llm"` routes the same pipeline through the OpenAI Chat
Completions API, on its own thread pool so a slow model cannot queue ahead of
mock jobs and push them past the latency budget. Credentials exist only as
server-side environment variables — clients never send a model key. The diff is
passed to the model as explicitly untrusted data inside `<diff>` tags with a
hardened system prompt, and the reply shape is enforced by Structured Outputs
(`response_format: json_schema`) rather than hoped for and parsed defensively.
If the model is unreachable, misconfigured or returns garbage, the job ends as
`failed` with a clear error message; the service never crashes.

`/v1/chat/completions` is the de-facto shape implemented by gateways and local
runtimes, so `LLM_BASE_URL` is a genuine lever: pointing it at a self-hosted
model needs no code change. Upstream error bodies are logged server-side and
deliberately not echoed into the client's error envelope.

## Docker

```bash
docker build -t diff-review-service .
docker run --rm -p 8080:8080 -e AUTH_TOKEN=my-secret diff-review-service
```

The image is a multi-stage build (Maven build stage → slim JRE runtime), so no
local toolchain is required.
