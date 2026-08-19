# SUBMISSION

## Architecture

Java 17 / Spring Boot 3, single service, no database — all state
(jobs, result cache, idempotency keys) lives in `ConcurrentHashMap`s, which I
consider the right trade-off for a scored 96-hour window on one instance; the
swap to Redis/Postgres is isolated behind three maps in one class.
`POST /v1/reviews` reads the body as **raw bytes**, then passes through the
guard chain: bearer-auth filter → sliding-window rate limiter → 1 MiB size
guard → manual JSON validation (400 vs 422 stay distinct) → unified-diff
parser → greedy file-boundary chunker. The job is queued on a fixed 4-thread
executor with an unbounded queue (4 running, the 5th waits, never fails) and
processed by a provider behind a single `ReviewProvider` interface. The
pipeline — not the provider — owns dedup by finding id, ordering
(path → line → ruleId), `maxFindings` truncation and the result cache, so
`mock` and `llm` share identical cross-cutting behavior. Every job appends its
SSE events to an append-only event log; live subscribers and later replays
read the same log, which is exactly why replay is byte-identical. All limits
declared by `/spec` are read from the same `AppProperties` bean the enforcing
components use, so declaration and behavior cannot drift.

## Provider design

- **mock** — a deterministic scanner implementing the rules table exactly.
  It walks only added lines with new-file line numbers derived from the `@@`
  hunk headers. The subtle rules got special care: empty `catch` may span
  lines and is judged on the *new file* (context lines inside the block count
  as content, the finding lands on the `catch` line); MOCK-005 uses a
  lookbehind regex so `=== null` / `!== null` never false-positive; MOCK-003
  requires the SQL keyword to be *inside a string literal* that is actually
  concatenated (`+` before/after, or `+=`). Injection content (MOCK-INJ) is
  reported as a finding and is inert by construction — the diff is only ever
  data to a regex engine, there is no code path where its text becomes an
  instruction.
- **llm** — the same pipeline, with the chunk's raw diff sent to the Anthropic
  Messages API (`ANTHROPIC_API_KEY`, `LLM_MODEL`, `LLM_BASE_URL` — server-side
  env vars only; clients never send a model key). The system prompt pins the
  diff as untrusted data inside `<diff>` tags. The model must return a JSON
  array; entries are validated and clamped (unknown severity/category →
  defaults, malformed entries dropped). Any failure — missing key, network,
  non-200, unparseable output — ends as a `failed` job with a clear error
  message. The service never crashes; a mock job submitted right after a
  failed llm job works normally (covered by a test).

## How I verified the cross-cutting behaviors

60 automated tests (JUnit 5 + AssertJ; black-box integration tests boot the
real server on a random port and speak plain HTTP), plus a manual curl smoke
test against the packaged jar.

The same contract is also asserted against the **deployed** instance by a
Postman/Newman conformance suite in `postman/` — 26 requests, 72 assertions,
runnable with one command (`npx newman run … --env-var token=…`). Tests that
pass in-process can still be defeated by a proxy, a reverse-proxy buffering an
SSE stream, or a stale image, so the deployment is verified over the wire too.

- **Chunking** — unit tests pin the greedy packing (small files share a chunk,
  60+60+2 KiB → 2 chunks, oversized file → its own chunk, order preserved).
  An integration test submits a real ~114 KiB three-file diff and asserts
  `usage.chunks == 2` **and** the exact finding ids — one per file, ordered,
  no duplicates, no losses — i.e. identical to an unchunked scan.
- **Caching** — submit, wait for `done`, resubmit byte-identical body: new
  jobId, `cacheHit: true`, findings compared as full JSON arrays for equality.
  A different `options.maxFindings` on the same diff is asserted to be a cache
  *miss* (options are part of the cache key).
- **Idempotency** — same key + same body → same jobId returned; same key +
  different body → 409 with `idempotency_conflict`. The key maps to the SHA-256
  of the raw request bytes, which is why the controller reads bytes, not a DTO.
- **SSE replay** — with a test-only processing delay (`MOCK_DELAY_MS`), the
  first connection is genuinely live (connects while the job runs) and the
  second is a replay of the finished job; the two full stream bodies are
  asserted **equal as strings**, plus event ordering (status → findings →
  done) and counts.
- Also covered: auth on every `/v1` route, the full error taxonomy, 429 with
  `Retry-After` while GETs stay unlimited, 4+1 concurrency under simulated
  latency, injection inertness, and llm graceful failure (base URL pointed at
  a closed port).

## AI tools used

Claude Code (Claude Fable 5) as the primary pair-programmer, end to end:
scaffolding, first drafts of every class, the test suite, and this document.
My role was directing the architecture, reviewing every draft against the
contract, rejecting/reshaping suggestions (below), and verifying behavior
through the test suite and manual curl runs. Work was committed per logical
unit — skeleton, error taxonomy, parser, chunker, providers, rate limiter,
pipeline, conformance suite — as each unit reached a state I had verified.
The AWS deployment runbook follows the same Docker → Docker Hub → EC2 flow
I had used before.

## AI suggestions I rejected, and why

1. **Typed `@RequestBody` DTO for POST /v1/reviews.** The first draft bound
   the body to a `ReviewRequest` record. I rejected it: idempotency requires
   comparing the *raw bytes* of the body (Jackson normalizes JSON, so two
   different byte sequences can deserialize equal), and Spring's own binding
   errors would leak its default error format instead of the contract's
   envelope, breaking the 400 `invalid_json` vs 422 `invalid_diff` split. The
   controller now reads bytes and validation is explicit.
2. **Substring matching for MOCK-005 (`contains("== null")`).** Rejected
   because `a === null` *contains* `== null`, so strict equality — which the
   rule's own title says is not a "loose null comparison" — would
   false-positive. Replaced with `(?<![=!])[!=]=\s*null`.
3. **Treating every line starting with `---` as a file header in the parser.**
   Rejected: a *removed* line whose content starts with two dashes renders as
   `--- x` inside a hunk and would silently corrupt the parse. The parser
   instead consumes hunks by the line counts declared in the `@@` header, so
   header-lookalikes inside hunks are impossible; a test pins this case.

## What I would do next with more time

- Redis-backed job store, cache and idempotency map + pub/sub fan-out for SSE,
  enabling horizontal scaling and restart-survival; TTL/eviction for jobs.
- In-flight dedup: a second identical submission arriving *while* the first is
  still running currently recomputes (results are still identical and the
  cache catches every later resubmission); I would coalesce them onto one
  future — this matters much more for the paid llm path than for mock.
- Retry with backoff + circuit breaker around the LLM call; per-chunk
  parallelism for multi-chunk llm jobs.
- Observability: Micrometer metrics (queue depth, cache hit rate, provider
  latency), structured logs with jobId correlation.
- CI (GitHub Actions: build + tests on push), OpenAPI spec, and a small
  load test for the rate limiter's sustained-rate guarantee.
