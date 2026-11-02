# Lucida Java SDK

Server SDK (Java 17+, Jackson only). Delivery is at-least-once: a retried event keeps its `uuid`, the server dedupes.

```java
Lucida.Options o = new Lucida.Options();
o.secretKey = "lc_sk_...";                       // flag definitions, importEvents
o.flagPollInterval = Duration.ofSeconds(30);     // background definitions polling
try (Lucida lc = new Lucida("lc_pk_...", o)) {
    lc.capture("user-1", "purchase", Map.of("total", 42));
    Object v = lc.getFeatureFlag("new-checkout", FlagContext.of("user-1"));
}                                                // close() stops threads and flushes
```

## Options

| Option | Meaning |
|---|---|
| `flushAt`, `flushInterval`, `maxQueueSize` | flush trigger, timer, bounded queue (overflow counts in `droppedEvents()`) |
| `maxBatchSize` | events per request (<= 1000), independent of `flushAt` |
| `maxRetries`, `retryBase` | exponential backoff with jitter; honours `Retry-After` |
| `requestTimeout` | per-request timeout for every HTTP call (default 10s) |
| `debug` | logs through `System.Logger` `dev.lucida` at INFO |
| `onError` | `Consumer<Throwable>` for dropped batches and failed flag fetches; its exceptions are swallowed |
| `beforeSend` | `UnaryOperator<ObjectNode>` over each event; return `null` to drop, throwing keeps the original |
| `flagPollInterval` | poll `/v1/flags/definitions` in the background (needs `secretKey`); **floor 5s**, shorter values are raised |
| `onlyEvaluateLocally` | never call the server for flags; undecidable ones read as unknown |
| `testModeFixtureJson`, `disabled`, `http` | offline fixture mode, switch-off, inject an `HttpClient` |

Definitions use `ETag` / `If-None-Match`; a `304` keeps the current set. `reloadFlags()` does one fetch on demand.

## Flags

Local evaluation first; flags it cannot decide (unknown key, cohorts, nothing loaded yet) go to `POST /v1/flags` unless `onlyEvaluateLocally`.

- `getFlagResult(key, ctx)` / `getFeatureFlagDetails(key, ctx)`: value, payload, variant, raw reason (the gateway's, `"remote"` if it sent none, `"fallback"`), `conditionIndex`, `requestId`, `evaluatedLocally`
- `Options.fallbacks` (`{flagKey: value}`, or `.fallbacks(Map)`): served with reason `"fallback"` when a flag cannot be evaluated (network error, nothing loaded, quota limited, unknown key); no exposure is sent for it
- `onFlagsChange(Runnable)` / `onFlagsStale(Runnable)`: definitions reloaded with a change / a reload failed
- `getFeatureFlag`, `getFeatureFlagPayload`, `getAllFlags`, `getAllFlagsAndPayloads`, `getAllFlagResults`
- `evaluate`, `isEnabled`: local only

## Historical import

```java
int n = lc.importEvents(List.of(new Lucida.ImportEvent("u1", "signup", Map.of(), Instant.parse("2020-01-02T03:04:05Z"), null, null, null)));
```

Needs `secretKey`; posts `/v1/s2s/batch` in chunks of `maxBatchSize`, timestamps kept; throws `LucidaException` on the first rejected chunk.

## Personless events

`lc.capturePersonless("page_seen", props)` or `Extras.PERSONLESS` sends `$process_person_profile=false`. With no distinct id a random UUID v4 is generated.

## Request context

```java
try (var scope = LucidaRequestContext.with(LucidaRequestContext.fromHeaders(headers, /*trustProxy*/ false, props))) {
    lc.capture(null, "checkout", Map.of());      // distinct id, $session_id and request props come from the scope
}
```

`fromHeaders` reads `X-Lucida-Distinct-Id` / `X-Lucida-Session-Id` (case-insensitive, control chars stripped, trimmed, capped at 1000 chars). They are client-controlled: **never use them for auth**. `props` carries `$current_url`, `$request_method`, `$request_path` and `$ip` (remote address; with `trustProxy` the first `X-Forwarded-For` entry); `$user_agent` is read from the headers. Explicit capture arguments override context values.

The optional `lucida-servlet` module (`sdks/java-servlet`, `dev.lucida.servlet.LucidaFilter`) is a `jakarta.servlet.Filter` that opens this context for each request (built against the Apache-2.0 `tomcat-servlet-api` as a provided dependency; the container supplies the real API). The core SDK stays Jackson-only. Register `new LucidaFilter()` once (`new LucidaFilter(true)` behind a proxy that overwrites `X-Forwarded-For`). On the legacy `javax.servlet` namespace, copy the ~15 lines of `LucidaFilter` and swap the imports.

Tests: `mvn test` (or `make sdk-java`).

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
