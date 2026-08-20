# Postman conformance suite

`AI-Diff-Review-Service.postman_collection.json` is an executable verification of the
API contract — 31 requests, 85 assertions, run in order against a live instance.

It is not a set of sample calls: every cross-cutting behaviour the contract specifies is
asserted, including the ones that cannot be seen from a single request.

## Run it

**Postman** — import the collection and `production.postman_environment.json`, select the
environment, set `token`, then **Run collection**.

**Newman** — no import needed:

```bash
npx newman run postman/AI-Diff-Review-Service.postman_collection.json --env-var baseUrl=http://91.99.219.7 --env-var token=YOUR_TOKEN
```

Against a local instance:

```bash
npx newman run postman/AI-Diff-Review-Service.postman_collection.json --env-var baseUrl=http://localhost:8080 --env-var token=YOUR_TOKEN
```

## What it asserts

| Step | Area | What is proven |
|------|------|----------------|
| 01–02 | Discovery | `/health` shape; `/spec` limits, cross-checked against real traffic in steps 19 and 23 |
| 03–05 | Auth | every `/v1` route rejects missing and invalid tokens with the error envelope; unauthenticated callers get 401 rather than 404, so the service leaks nothing |
| 06–07 | Review pipeline | all nine mock rules fire exactly once; `path → line → ruleId` ordering; line numbers derived from hunk headers; dedup; three near-miss cases stay clean; injected instructions reported as data, never obeyed |
| 08–09 | SSE | event sequence `status → finding × n → done`, and a finished job replays **byte-identically** |
| 10–13 | Caching | a run-unique diff misses, then a byte-identical resubmit hits with deep-equal findings under a *new* job id |
| 14–16 | Idempotency | same key + same body returns the same job; same key + different body is a 409 |
| 17–18 | maxFindings | the ordered list truncates while `usage` still describes the full scan |
| 19–20 | Chunking | a generated 87 KiB diff splits on file boundaries; findings identical to an unchunked scan — no duplicates, no losses, ordering preserved |
| 21–24 | Error taxonomy | 400 / 422 / 413 / 404, each with its machine-readable code; the 1 MiB guard rejects without a 5xx |
| 25–26 | llm provider | the model path terminates cleanly either way, and the service is still serving afterwards |
| 27 | llm provider | the model path is **actually configured** on the server — see the note below |
| 28–29 | Parser tolerance | a diff with wrong `@@` counts and no `---`/`+++` pair still yields every added line, at its true line number, with the path recovered from `diff --git` |
| 30 | Auth | the bearer scheme is matched case-insensitively, per RFC 7235 |
| 31 | Error taxonomy | the envelope also covers the framework's own error path — no HTML escapes on any non-2xx |

### Step 27 fails on purpose when the model is not configured

The contract requires the `llm` provider to be *fully configured on the server*, which is a
stronger claim than "it degrades gracefully" — and one a suite that accepts either outcome
can never make. Step 27 asserts the job reaches `done`, so an unset `OPENAI_API_KEY`
turns the suite red and names itself in the failure message:

```
llm job did not reach done (server said: llm provider is not configured on this server
(OPENAI_API_KEY is not set))
```

Fix it on the server, not here: add the key to `/srv/backend/diff-review-service/.env` and
redeploy. Step 26 still covers graceful degradation independently, so nothing is lost by
step 27 being strict.

### What is deliberately not here

Rate limiting and 4-way concurrency: a burst would eat into the shared budget of a scored
run, and concurrency needs parallel in-flight jobs, which a sequential runner cannot
produce. Both are covered by the JUnit suite — `RateLimiterSustainedRateTest` (the sustained
30/min guarantee, on a virtual clock), `RateLimitWallClockTest` (the same guarantee measured
over real time through the full HTTP stack), `RateLimitTest` and `ConcurrencyTest`.

## Design notes

- **Re-runnable.** Cache and idempotency steps generate run-unique payloads, so a second run
  is not polluted by the first run's cache entries and idempotency keys.
- **Safe on a cold service.** Result reads poll until the job is terminal instead of assuming
  it finished, so the suite passes even on a just-started instance.
- **Byte-identical bodies where it matters.** The canonical diff is built once in the
  collection pre-request script and shared by reference, so the caching and idempotency steps
  provably send identical bytes rather than merely similar-looking JSON.
- **Large payloads are generated, not stored.** The 87 KiB chunking diff and the 1.1 MB
  oversized body are built in pre-request scripts into *local* variables, keeping them out of
  the saved collection.
- **No token in version control.** `token` is an empty variable. The service flags hardcoded
  credentials (rule MOCK-002), so committing one here would contradict the tool.
