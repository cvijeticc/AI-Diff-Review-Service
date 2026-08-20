# SUBMISSION

## Architecture

Java 17 / Spring Boot 3, single service, no database — all state (jobs, result
cache, idempotency keys) lives in three in-process Caffeine caches, bounded by
size and age, which I consider the right trade-off for a scored 96-hour window
on one instance; the swap to Redis/Postgres is isolated behind three fields in
one class.
`POST /v1/reviews` reads the body as **raw bytes**, then passes through the
guard chain: bearer-auth filter → token-bucket rate limiter → 1 MiB size
guard → manual JSON validation (400 vs 422 stay distinct) → unified-diff
parser → greedy file-boundary chunker. The job is queued on a fixed 4-thread
executor with an unbounded queue (4 running, the 5th waits, never fails) and
processed by a provider behind a single `ReviewProvider` interface; `llm` jobs
get their own pool, so a slow model cannot queue ahead of mock jobs and push
them past the latency budget. The pipeline — not the provider — owns
dedup by finding id, ordering
(path → line → ruleId), `maxFindings` truncation and the result cache, so
`mock` and `llm` share identical cross-cutting behavior. Every job appends its
SSE events to an append-only event log; live subscribers and later replays
read the same log, which is exactly why replay is byte-identical. All limits
declared by `/spec` are read from the same `AppProperties` bean the enforcing
components use — `burstLimit` straight off the limiter object — so declaration
and behavior cannot drift. Jobs, cached results and idempotency keys are
bounded by size and age; keys outlive jobs on purpose, which is why every
lookup of a job id tolerates a miss.

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

90 automated tests (JUnit 5 + AssertJ; black-box integration tests boot the
real server on a random port and speak plain HTTP), plus a manual curl smoke
test against the packaged jar.

The same contract is also asserted against the **deployed** instance by a
Postman/Newman conformance suite in `postman/` — 31 requests, 85 assertions,
runnable with one command (`npx newman run … --env-var token=…`). Tests that
pass in-process can still be defeated by a proxy, a reverse-proxy buffering an
SSE stream, or a stale image, so the deployment is verified over the wire too.

- **Rate limiting** — the contract's guarantee is *"sustained 30
  submissions/minute must succeed"*, and my original test did not test it. It
  asserted that a burst produces 429 with `Retry-After`, which is the opposite
  property: that the limit *engages*, never that it stays out of the way when
  it must. Measured over wall-clock time, the fixed sliding window failed at
  the 35th request while running at exactly the guaranteed rate — a window of
  30-in-60s has literally zero headroom at 30/min, so any jitter or GC pause
  puts a request over the edge, and after a burst it refuses *everything* for a
  full 60 s. It is now a token bucket: `rateLimitPerMinute` is the refill rate
  (so the sustained rate consumes exactly what has already been put back and
  cannot fail), `rateLimitBurst` the declared, published allowance for arriving
  faster, and recovery is one token — about 2 s — instead of a whole window.
  Two tests hold that line: one runs the real 30/min for ten simulated minutes
  on a virtual clock, the other reproduces the original experiment over real
  time through the full HTTP stack. I checked the second one discriminates by
  reverting to the old limiter: all 20 sustained requests come back 429.

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
- **The parser, against input it will actually get** — the hunk line counts are
  load-bearing (see rejected suggestion 3), but they are also the part a
  hand-written or generated diff gets wrong most often. Tests pin both halves:
  while the counts run the walk is exactly as strict as before, so a removed
  line rendering as `--- x` still cannot be read as a file header; once they are
  exhausted or unsatisfied, the tail is still consumed, the hunk ends cleanly at
  the next section, and a missing `---`/`+++` pair falls back to the path in
  `diff --git`. Three tests confirm the tolerance does not turn prose, a bare
  file header or a bare `diff --git` line into a valid diff.
- **The llm path when the model answers** — `LlmProviderContractTest` runs the
  provider against a local stub speaking the Anthropic Messages API: a normal
  reply becomes findings through the same pipeline as mock, the configured
  `max_tokens` and model are asserted to be what actually goes on the wire, the
  diff is asserted to arrive fenced as data, malformed entries are dropped
  rather than failing the job, and a reply truncated at `max_tokens` fails with
  a message naming that cause instead of an unexplained JSON parse error.
- **Resource isolation** — `ExecutorIsolationTest` queues twelve deliberately
  slow llm jobs and then submits a mock job, asserting it finishes in well under
  one round of model latency. On a single shared pool that job waits behind
  three rounds and misses the budget through no fault of its own.
- **Bounded retention** — `JobRetentionTest` squeezes the job TTL to a second
  while leaving the key TTL long, reproducing in seconds the state the defaults
  reach after a day, and proves the paths expiry creates: an expired job id
  reads as 404 with the envelope on both read routes, an idempotent replay whose
  job is gone redoes the work instead of dereferencing a null, a *conflicting*
  body under a surviving key is still a 409, and cached findings outlive the job
  that produced them.
- Also covered: auth on every `/v1` route (including the RFC 7235
  case-insensitive scheme), the full error taxonomy — including the responses
  the application never writes itself, the framework's error page and the
  container's own pre-servlet rejections — 429 with `Retry-After` while GETs
  stay unlimited, 4+1 concurrency under simulated latency, injection inertness,
  and llm graceful failure (base URL pointed at a closed port).

## AI tools used

Claude Code (Claude Fable 5) as the primary pair-programmer, end to end:
scaffolding, first drafts of every class, the test suite, and this document.
My role was directing the architecture, reviewing every draft against the
contract, rejecting/reshaping suggestions (below), and verifying behavior
through the test suite and manual curl runs. Work was committed per logical
unit — skeleton, error taxonomy, parser, chunker, providers, rate limiter,
pipeline, conformance suite — as each unit reached a state I had verified.
Deployment is a multi-stage Docker image built on the server and run under
docker compose, driven from GitHub Actions over an SSH key restricted by a
forced command; that shape follows a runbook I had used before.

I also used it the other way round, which turned out to be worth more than any
of the drafting: I had it write an adversarial probe against my own running
service — malformed diffs, boundary regexes, path-normalisation attempts,
oversized payloads, a wall-clock measurement of the rate limiter — and treated
the output as a bug report rather than a review. It found two real defects that
90 tests written alongside the code had all missed, because tests written
alongside code inherit its assumptions: the rate limiter broke the guarantee it
existed to provide, and the parser dropped findings without saying so. Both are
described above and both are fixed.

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
4. **My own parser, after I used AI to attack it.** I had Claude Code write an
   adversarial probe against my running service rather than more tests around
   the code it had just written. It found that a `@@` header undercounting its
   own hunk made the parser drop the remaining added lines *silently* — HTTP
   202, status `done`, findings simply missing. None of my tests caught it,
   because they all fed the parser diffs I had written correctly. The fix keeps
   suggestion 3's argument intact rather than trading it away: the counts stay
   authoritative while they run, and only what the counts cannot vouch for
   became tolerant. The wider lesson is the one I would repeat: point the AI at
   the finished thing as an adversary, not at the blank page as an author.

## Decisions that are deliberate, not oversights

Each of these is a case where the contract is silent or two readings are
defensible, so it is worth saying which reading I took and why.

- **MOCK-003 matches SQL keywords case-sensitively.** `"select * from t" + id`
  does not fire. Lowercase prose keywords are common in ordinary strings
  (`"Please update your profile" + name`), and a false positive on a security
  rule costs more than a miss on a lowercase SQL literal. Backtick template
  literals *do* fire, including via `${...}` interpolation, because that is the
  same injection spelled differently.
- **A `catch` block containing only a comment is not reported.** This matches
  ESLint's `no-empty` semantics, which treats a comment as an explicit
  acknowledgement rather than a swallowed error.
- **An idempotent replay returns the job's real status**, so a replay of a
  finished job answers `done` rather than the `queued` shown in the contract's
  example. The example illustrates the shape of the response, and reporting a
  status the job does not have seemed the worse of the two readings; the `202`
  and the identical `jobId` are unchanged either way.
- **The rate limit is global, not per token or per IP.** In this scope one
  client holds one token, so the two are the same thing; per-token buckets
  would be a map keyed by token in the same class.
- **`streamExecutor` is a cached pool**, one thread per open SSE connection.
  Correct and simple at this concurrency; the next step is async SSE or a
  bounded pool, which matters at a connection count this service will not see.

## What I would do next with more time

- Redis-backed job store, cache and idempotency map + pub/sub fan-out for SSE,
  enabling horizontal scaling and restart-survival. Retention is already
  bounded by size and age in `ReviewService`; Redis would move it off-process.
- In-flight dedup: a second identical submission arriving *while* the first is
  still running currently recomputes (results are still identical and the
  cache catches every later resubmission); I would coalesce them onto one
  future — this matters much more for the paid llm path than for mock.
- Retry with backoff + circuit breaker around the LLM call; per-chunk
  parallelism for multi-chunk llm jobs.
- Observability: Micrometer metrics (queue depth, cache hit rate, provider
  latency), structured logs with jobId correlation.
- OpenAPI spec generated from the same `AppProperties` bean `/spec` reads, so
  the three declarations stay one declaration.
- Per-token rate limiting and a metrics endpoint for bucket depth, so the limit
  is observable rather than only testable.
