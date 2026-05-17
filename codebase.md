# Codebase Analysis Snapshot

The goal of this file is to capture the current analysis of the `VertiLB` codebase so it can be used for brainstorming with ChatGPT.

## 1. Quick Snapshot

- Project name: `VertiLB`
- Role: API gateway + load balancer built with Java/Vert.x
- Main stack:
  - Java `21`
  - Gradle `8.x`
  - Vert.x `4.5.x`
  - Jackson for JSON config
  - SLF4J + Logback for logging
- Current size:
  - `37` source files in `src/main/java`
  - `16` test files in `src/test/java`
- There are `2` mock backend services in the smoke flow:
  - `user-service`
  - `order-service`

## 2. High-Level Architecture

The codebase is split into clear modules:

- `io.vertilb.VertiLB`
  - composition root
  - loads config
  - builds pools
  - creates `GatewayRouter`, `CoreEngine`, `HttpProxy`
  - deploys listeners, health checkers, and metrics verticle
- `config`
  - config models + `ConfigLoader`
  - applies defaults + validates + cross-validates
- `gateway`
  - route matching by `host`, `method`, `pathPrefix`
  - URI rewriting via `stripPrefix` / `addPrefix`
- `http`
  - `ListenerVerticle` receives client requests
- `engine`
  - `CoreEngine` orchestrates request lifecycle, retry, logging, and metrics
- `pool`
  - runtime upstream state + balancing strategy
- `proxy`
  - `HttpProxy` forwards requests to upstreams and streams responses back
- `health`
  - `HealthChecker` probes upstreams periodically and updates health
- `observability`
  - `AppLogger`, `MetricsCollector`, `MetricsVerticle`, `PrometheusMetricsFormatter`

Current request flow:

`Client -> ListenerVerticle -> GatewayRouter -> CoreEngine -> UpstreamPool -> BalancingStrategy -> HttpProxy -> Upstream`

Background flow:

`HealthChecker -> UpstreamPool`

Observability flow:

`CoreEngine / HealthChecker -> MetricsCollector -> MetricsVerticle`

## 3. Current System Behavior

### Routing

- Matches by:
  - `host`
  - `HTTP method`
  - `pathPrefix`
- Supports URI rewriting:
  - `stripPrefix`
  - `addPrefix`
- If no route matches:
  - listener returns `404`

### Load balancing

There are currently 4 strategies:

- `round-robin`
- `random`
- `ip-hash`
- `least-connections`

### Retry

- Retries only safe methods:
  - `GET`
  - `HEAD`
  - `OPTIONS`
- Retry behavior is driven by `RetryPolicy`
- `RetryPolicy` is built from:
  - `defaults.retries.maxAttempts`
  - `defaults.retries.retryableStatuses`
  - `defaults.retries.backoffMs`
- `HttpProxy` receives retry-candidate statuses from config so retryable responses can be deferred to `CoreEngine`

### Health check

- Periodically probes each upstream
- Uses thresholds:
  - `successThreshold`
  - `failureThreshold`
- Updates health state:
  - `UNKNOWN`
  - `HEALTHY`
  - `UNHEALTHY`

### Metrics

- In-memory metrics
- `GET /metrics` returns JSON
- `GET /metrics/prometheus` returns Prometheus-compatible text
- Current metric groups:
  - `totalRequests`
  - `requestsByPool`
  - `statusCodeBuckets`
  - `upstreamRequestCounts`
  - `errorCounts`
  - `latencySamples`
  - `latencySummary`
  - `poolStats`

## 4. Current Config Model

The JSON config includes these main sections:

- `listeners`
- `routes`
- `pools`
- `upstreams`
- `defaults`
- `metrics`
- `healthCheck`

`ConfigLoader` currently does 3 main things:

- loads JSON
- applies defaults
- validates rules + cross-validates

Current defaulting/validation behavior includes:

- listener ports must be valid and non-duplicate
- pool names must be non-duplicate
- routes must reference an existing pool
- upstream protocol must be `http` or `https`
- `metrics.port` must not overlap a listener port
- retry config is validated
- health check config is validated
- `healthCheck.unknownSelectable` defaults to `true`

## 5. Scripts And Tests

### Scripts

Current contents of `scripts/`:

- `mock-backend.py`
  - generic mock backend server
- `smoke-gateway.sh`
  - compiles the project
  - starts 2 mock backends
  - starts VertiLB
  - checks users/orders routes
  - checks unmatched route
  - checks retryable `503`
  - checks metrics endpoint
- `bench-gateway.sh`
  - compiles VertiLB
  - starts 2 mock backends
  - starts VertiLB
  - waits for gateway and metrics readiness
  - runs `hey` benchmarks with warmup, repeated runs, and CSV output
  - writes aggregate benchmark CSV files from repeated measured runs
  - supports an optional direct-backend baseline

### Current test coverage

There are tests for:

- `ConfigLoader`
- `RequestContext`
- `GatewayRouter`
- `HealthChecker`
- `HealthState`
- `MetricsCollector`
- `MetricsVerticle`
- `PrometheusMetricsFormatter`
- `UpstreamPool`
- `StrategyFactory`
- `RoundRobinStrategy`
- `IpHashStrategy`
- `LeastConnectionsStrategy`
- main request-path integration via `GatewayRequestPathIntegrationTest`

Assessment:

- Test coverage is fairly good for config, routing, pool strategy, observability, and health.
- Dedicated higher-level integration coverage now exists for `ListenerVerticle -> GatewayRouter -> CoreEngine -> UpstreamPool -> HttpProxy -> backend`.
- Remaining integration gaps are mostly around failure modes, request bodies, multiple upstreams, and production-like metrics/health interactions.

## 6. Measured Stress Test Results

Local benchmark context:

- endpoint: `GET /api/users/1?debug=true`
- 1 healthy upstream
- persistent HTTP/1.1 connections
- `10s` per run
- same-host local benchmark using the same Python persistent-connection harness as the earlier run
- mock backends: `user-service` and `order-service`
- route under test: `user-service`

### Latest Run After Performance Hardening Phase 1

Date: `2026-05-17`

Relevant code state:

- `UpstreamPool` selectable upstream caching has been applied.
- `selectUpstream(ctx)` reads a cached `volatile List<Upstream>` snapshot.
- Cache rebuild happens when `updateHealthStatus(...)` changes an upstream status.
- Default health semantics remain optimistic: `UNKNOWN` and `HEALTHY` are selectable, `UNHEALTHY` is excluded.
- Strict startup can be enabled per pool with `healthCheck.unknownSelectable=false`.
- No object pooling was added.

Results:

| Concurrency | Requests | QPS | Avg Latency | P95 | P99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 2,598 | 259.4 | 3.9 ms | 11.4 ms | 20.1 ms | 0 |
| 16 | 5,841 | 582.5 | 27.4 ms | 37.6 ms | 43.3 ms | 0 |
| 64 | 8,079 | 802.1 | 79.5 ms | 111.6 ms | 145.1 ms | 0 |
| 128 | 9,058 | 893.8 | 142.1 ms | 181.6 ms | 231.1 ms | 0 |

Latest key result:

- Peak observed throughput in this comparable run was `~894 QPS` at `128` concurrent clients.
- VertiLB metrics reported `25,577` total requests, all status `200`, with no recorded errors.
- Runtime logs were kept at `/tmp/vertilb-bench.1eyqMe`.

Comparison to previous local run:

- Previous peak: `~944 QPS` at `128` concurrent clients.
- Latest peak: `~894 QPS` at `128` concurrent clients.
- Difference: about `-5.3%` in this single same-host run.
- This should not be treated as a confirmed regression without repeated runs, because local same-host benchmarks can vary due to JVM warmup, scheduler noise, Gradle/runtime state, and backend behavior.

### Performance Hardening Phase 1.5 - Benchmark Discipline

Date: `2026-05-17`

Scope:

- Only `scripts/bench-gateway.sh` was changed.
- No production Java code was changed.
- No object pooling was added.
- `UpstreamPool`, `GatewayRouter`, `CoreEngine`, `HttpProxy`, and `HealthChecker` were not modified in this phase.

Benchmark script behavior after Phase 1.5:

- Default benchmark now runs multiple concurrencies via `BENCH_CONCURRENCY_LIST=1,16,64,128`.
- Backward compatibility is preserved: setting `BENCH_CONCURRENCY` runs only that one concurrency.
- Each concurrency gets one warmup run using `BENCH_WARMUP_DURATION`, default `10s`.
- Each concurrency gets repeated measured runs using `BENCH_RUNS`, default `3`.
- Raw `hey` output is saved per run:
  - `bench-c${concurrency}-warmup.log`
  - `bench-c${concurrency}-run${run}.log`
- Machine-readable gateway summary is saved to `benchmark-summary.csv`.
- Optional direct-backend baseline is enabled with `DIRECT_BACKEND_BASELINE=true`.
  - URL: `http://localhost:${USER_BACKEND_PORT}/users/1?debug=true`
  - summary file: `direct-backend-summary.csv`
- If `KEEP_BENCH_LOGS=true`, the temp directory path is printed and logs are retained.
- If logs are not retained, the readable summary is still printed to stdout before cleanup.

CSV columns:

```csv
timestamp,concurrency,run,duration,requests_per_sec,avg_latency_ms,p95_latency_ms,p99_latency_ms,status
```

Full local verification run with preserved artifacts:

- Command: `KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- Artifact directory: `/tmp/tmp.LJjvHIW7P0`
- Summary file: `/tmp/tmp.LJjvHIW7P0/benchmark-summary.csv`

Sample CSV rows:

```csv
timestamp,concurrency,run,duration,requests_per_sec,avg_latency_ms,p95_latency_ms,p99_latency_ms,status
2026-05-17T10:45:35Z,1,1,30s,669.2995,1.500,2.800,5.200,ok
2026-05-17T10:46:05Z,1,2,30s,797.6881,1.300,2.200,4.600,ok
2026-05-17T10:47:15Z,16,1,30s,1234.3639,13.000,19.500,23.500,ok
2026-05-17T10:51:55Z,128,3,30s,1235.8744,103.400,124.000,153.400,ok
```

Short direct-backend baseline verification:

- Command: `DIRECT_BACKEND_BASELINE=true BENCH_CONCURRENCY=1 BENCH_RUNS=1 BENCH_DURATION=1s BENCH_WARMUP_DURATION=1s KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- Artifact directory: `/tmp/tmp.9pRaPO7EOe`
- Gateway sample result: `213.1737 QPS`, `4.600 ms` average latency
- Direct backend sample result: `1054.9324 QPS`, `0.900 ms` average latency

Verification commands run:

- `bash -n scripts/bench-gateway.sh`
- `./gradlew clean compileJava`
- `./gradlew clean test`
- `./scripts/smoke-gateway.sh`
- `./scripts/bench-gateway.sh`
- `KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- direct-backend baseline short check
- `git diff --check`

Deviation:

- The first plain benchmark attempt failed inside the sandbox because Gradle could not write its lock file under `~/.gradle`.
- The benchmark commands were rerun outside the sandbox with approval and completed successfully.

### Benchmark Discipline Phase - Single 1024-Concurrency Stress Benchmark

Date: `2026-05-17`

Scope:

- Only `scripts/bench-gateway.sh` was changed.
- No production Java code was changed.
- No object pooling was added.
- `CoreEngine`, `HttpProxy`, `GatewayRouter`, `UpstreamPool`, `HealthChecker`, `MetricsCollector`, and `ConfigLoader` were not modified in this phase.

Benchmark script behavior after this phase:

- Default benchmark now runs a single target concurrency with `BENCH_CONCURRENCY=1024`.
- The script no longer defaults to a multi-concurrency matrix.
- `BENCH_CONCURRENCY` still allows focused overrides such as `128`, `512`, or `1024`.
- Warmup is preserved with `BENCH_WARMUP_DURATION`, default `10s`.
- Repeated measured runs are preserved with `BENCH_RUNS`, default `3`.
- Measured run duration remains `BENCH_DURATION`, default `30s`.
- Gateway benchmark URL remains `http://localhost:${GATEWAY_PORT}/api/users/1?debug=true`.
- Optional direct backend baseline remains available with `DIRECT_BACKEND_BASELINE=true`.

CSV schema after this phase:

```csv
timestamp,target,concurrency,run,duration,qps,avg_latency_ms,p95_latency_ms,p99_latency_ms,status
```

Raw logs after this phase:

- gateway warmup: `bench-gateway-c${BENCH_CONCURRENCY}-warmup.log`
- gateway runs: `bench-gateway-c${BENCH_CONCURRENCY}-run${run}.log`
- direct backend warmup: `bench-direct-c${BENCH_CONCURRENCY}-warmup.log`
- direct backend runs: `bench-direct-c${BENCH_CONCURRENCY}-run${run}.log`

Full local verification run with preserved gateway-only artifacts:

- Command: `KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- Artifact directory: `/tmp/tmp.kfF0SIQ9ct`

Sample gateway CSV rows:

```csv
timestamp,target,concurrency,run,duration,qps,avg_latency_ms,p95_latency_ms,p99_latency_ms,status
2026-05-17T11:22:13Z,gateway,1024,1,30s,903.4774,1114.200,1504.300,1632.600,ok
2026-05-17T11:22:45Z,gateway,1024,2,30s,979.2417,1025.500,1198.400,1248.800,ok
2026-05-17T11:23:16Z,gateway,1024,3,30s,1021.0639,985.500,1147.700,1189.700,ok
```

Full local verification run with preserved gateway and direct-backend artifacts:

- Command: `DIRECT_BACKEND_BASELINE=true KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- Artifact directory: `/tmp/tmp.EDudsDewqg`

Sample direct-backend CSV rows:

```csv
timestamp,target,concurrency,run,duration,qps,avg_latency_ms,p95_latency_ms,p99_latency_ms,status
2026-05-17T11:27:08Z,direct-backend,1024,1,30s,840.7706,180.600,1026.200,4141.900,ok
2026-05-17T11:27:52Z,direct-backend,1024,2,30s,827.2760,222.000,1061.500,5375.000,ok
2026-05-17T11:28:36Z,direct-backend,1024,3,30s,832.0302,140.500,1015.100,3061.800,ok
```

### Benchmark Summary Aggregation

Date: `2026-05-17`

Scope:

- Only `scripts/bench-gateway.sh` was changed.
- No Java code was changed.
- No object pooling or performance refactor was added.

Benchmark script behavior after this phase:

- Per-run CSV output remains unchanged.
- The script now writes aggregate CSV output after measured runs:
  - gateway: `benchmark-aggregate.csv`
  - direct backend: `direct-backend-aggregate.csv` when `DIRECT_BACKEND_BASELINE=true`
- Aggregates are computed from measured runs only, not warmup.
- Aggregate fields include:
  - QPS min/max/avg/median
  - average latency min/max/avg/median
  - status: `ok`, `partial`, or `failed`
- The script also prints a readable aggregate table to stdout.

Aggregate CSV schema:

```csv
target,concurrency,runs,qps_min,qps_max,qps_avg,qps_median,avg_latency_min_ms,avg_latency_max_ms,avg_latency_avg_ms,avg_latency_median_ms,status
```

Short verification run with preserved artifacts:

- Command: `BENCH_RUNS=2 BENCH_DURATION=3s BENCH_WARMUP_DURATION=1s KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`
- Artifact directory: `/tmp/tmp.MCYWgnLQQv`

Sample aggregate row:

```csv
target,concurrency,runs,qps_min,qps_max,qps_avg,qps_median,avg_latency_min_ms,avg_latency_max_ms,avg_latency_avg_ms,avg_latency_median_ms,status
gateway,1024,2,587.624,737.810,662.717,662.717,1162.800,1447.100,1304.950,1304.950,ok
```

Deviation:

- The first sandboxed benchmark attempt failed because Gradle could not write its wrapper lock under `~/.gradle`.
- The benchmark was rerun outside the sandbox with approval and completed successfully.

### Previous Local Run

Results:

| Concurrency | Requests | QPS | Avg Latency | P95 | Errors |
|---|---:|---:|---:|---:|---:|
| 1 | 2,644 | 264.3 | 3.8 ms | 10.7 ms | 0 |
| 16 | 6,115 | 609.8 | 26.2 ms | 37.8 ms | 0 |
| 64 | 8,224 | 816.0 | 78.1 ms | 104.6 ms | 0 |
| 128 | 9,573 | 944.2 | 134.5 ms | 160.2 ms | 0 |

Key result:

- Peak observed throughput in this run was `~944 QPS` at `128` concurrent clients.

Notes:

- This was a same-host local benchmark.
- This is not yet a production benchmark.
- The true throughput plateau was not found because testing only went up to `128` concurrency.

## 7. Strengths Of The Codebase

- Clear module separation and readable structure.
- The request flow is straightforward and easy to trace.
- The config model is already fairly complete for a small gateway.
- The balancing strategies are diverse enough for demos and future extension.
- Health checker and metrics are sufficient for local/staging usage.
- The smoke script gives a fast end-to-end verification path.
- README and code structure are reasonably aligned.

## 8. Gaps / Risks / Ambiguities Worth Brainstorming

### 8.1 Retry config is now wired through runtime

Current runtime behavior:

- `CoreEngine` delegates retry decisions to `RetryPolicy`
- `HttpProxy` receives retry-candidate statuses from the same config-derived policy
- Retry backoff uses non-blocking `vertx.setTimer(...)`

Meaning:

- Retry behavior is now config-driven end to end
- The remaining documentation concern in this area is keeping snapshots current as retry behavior evolves

### 8.2 Logging level is wired through AppLogger runtime filtering

- `ConfigLoader` defaults and validates `defaults.logging.level`
- Supported levels are `ERROR`, `WARN`, `INFO`, `DEBUG`, and `TRACE`
- `VertiLB` constructs `AppLogger` with `config.defaults.logging.level`
- `AppLogger` suppresses messages below the configured level before calling SLF4J

Meaning:

- Setting `logging.level` now affects logs emitted through `AppLogger`
- This does not dynamically rewrite global Logback configuration

### 8.3 Method names and semantics do not fully match

- `UpstreamPool.getHealthyUpstreams()` is named as if it returns only healthy upstreams
- Actual implementation returns selectable upstreams
- `UNKNOWN + HEALTHY` are both selectable

Meaning:

- This can mislead readers and maintainers
- The API now prefers `getSelectableUpstreams()`
- `getHealthyUpstreams()` remains only as a legacy compatibility alias
- Naming does not fully reflect behavior

### 8.4 Health semantics are intentionally optimistic

By default:

- `Upstream.isSelectable()` allows `UNKNOWN`
- `UNKNOWN` and `HEALTHY` upstreams are selectable
- `UNHEALTHY` upstreams are excluded

This is optimistic startup behavior:

- New upstreams can receive traffic before the first successful health check.
- Cold start is smoother because traffic is not blocked while health status is still unknown.
- Operators should understand that `UNKNOWN` means not yet proven healthy, not unavailable.

Strict startup behavior is now opt-in per pool:

- `healthCheck.unknownSelectable=false` excludes `UNKNOWN` upstreams.
- In strict mode, only `HEALTHY` upstreams are selectable.
- `UNHEALTHY` upstreams remain excluded in all modes.

### 8.5 Metrics are still basic in-memory snapshots

- Simple and easy to use
- Good enough for local and smoke environments
- Not yet production-grade observability
- Prometheus-compatible text output now exists at `/metrics/prometheus`

Current limits:

- latency samples are capped at `10,000`
- no true histogram buckets
- no persistence/export pipeline
- no explicit Prometheus `HELP` / `TYPE` metadata yet
- Prometheus latency metrics are summary values from in-memory samples, not histogram buckets

### 8.6 Benchmark discipline has improved, but interpretation is still early

- There is a smoke test.
- There is a committed benchmark script at `scripts/bench-gateway.sh`.
- The benchmark script now supports:
  - a single default `1024`-concurrency stress run
  - `BENCH_CONCURRENCY` override for focused runs
  - warmup runs
  - repeated measured runs
  - raw per-run logs
  - machine-readable CSV summaries
  - aggregate CSV summaries
  - optional direct-backend baseline comparison

Meaning:

- The benchmark harness is now good enough to avoid overreacting to a single local run.
- Results should still be interpreted carefully because same-host JVM/backend/client benchmarks are noisy.
- Future optimization work should compare repeated run distributions, not one QPS number.
- Aggregate summaries make min/max/avg/median visible, but they still do not explain root cause by themselves.

## 9. Good Brainstorm Directions

### Direction 1: Make config the real source of truth

Questions:

- Are there config fields that appear validated but are not yet enforced by runtime components?
- Should future logging work update global Logback configuration or keep filtering localized to `AppLogger`?

### Direction 2: Upgrade observability

Questions:

- Should Prometheus output add `HELP` and `TYPE` metadata?
- Should `latencySamples` be replaced with fixed histogram buckets?
- Should per-route and per-method metrics be added?
- Should metrics include retry attempt counts and upstream failure categories?

### Direction 3: Evolve health semantics

Current decision:

- `UNKNOWN` and `HEALTHY` upstreams are selectable by default
- `UNHEALTHY` upstreams are excluded
- This is optimistic startup behavior
- Strict startup mode exists with `healthCheck.unknownSelectable=false`

Questions:

- Is there a need for circuit breaking or outlier detection?

### Direction 4: Performance and scale

Questions:

- What is the actual QPS plateau of this gateway?
- Where is the current bottleneck:
  - listener
  - router
  - core engine
  - proxy
  - mock backend
- Should more benchmarks be added for:
  - direct backend vs through LB
  - multiple upstreams
  - multiple listeners
  - POST/body streaming
- Should benchmark aggregate summaries add stddev and coefficient of variation?
- Should benchmark runs collect CPU, heap, GC, and event-loop latency alongside QPS?

### Direction 5: Productization

Questions:

- Should config hot reload be supported?
- Should true weighted balancing be added?
- Should the gateway add rate limiting, auth, circuit breaker, request hedging, or canary routing?

### Direction 6: Test strategy

Questions:

- Should integration tests be expanded for multi-upstream retry/failover?
- Should integration tests cover request bodies and response streaming?
- Should metrics and health behavior be tested together in an integration scenario?
- Should there be a lightweight performance regression test?

## 10. Opinionated Notes And Pushback

These are intentionally direct notes for brainstorming, not final decisions.

1. Do not optimize object allocation yet.
   The benchmark harness only recently became disciplined enough to show distributions. The next performance step should identify the bottleneck with evidence: direct backend comparison, CPU usage, GC, event-loop delay, and upstream latency.

2. The Prometheus endpoint is useful, but it is not production observability yet.
   It exposes text metrics, but latency is still derived from capped in-memory samples. Real production observability would likely need histogram buckets, route labels, method labels, retry labels, and stable metric metadata.

3. The current integration test is valuable, but it covers the happy path plus one retry-exhaustion path.
   It should not create false confidence about POST/body streaming, upstream disconnects, timeout behavior, multiple upstream failover, or health transition races.

4. Strict health mode is a good operator control, but the default optimistic behavior can still surprise production users.
   The right default depends on deployment style. Optimistic startup favors local/dev and fast cold starts; strict startup favors safety when upstream readiness matters.

5. Adding product features now may dilute the core gateway.
   Rate limiting, auth, circuit breaker, hedging, and canary routing are all plausible, but each one changes the gateway's operational contract. The near-term roadmap should prefer correctness, observability, test depth, and benchmark clarity first.

6. `CoreEngine` is becoming the coordination center.
   That is acceptable for now, but future features should be careful not to make it own policy details. RetryPolicy, health selection, routing, and proxying should remain separately testable boundaries.

## 11. Sample Prompt To Bring To ChatGPT

You can copy this prompt:

```text
I have a Java/Vert.x codebase called VertiLB that acts as an API gateway and load balancer.

Current summary:
- Architecture: ListenerVerticle -> GatewayRouter -> CoreEngine -> UpstreamPool -> BalancingStrategy -> HttpProxy -> Upstream
- It has a health checker, in-memory metrics, JSON metrics at /metrics, and Prometheus text metrics at /metrics/prometheus
- JSON config includes listeners/routes/pools/upstreams/defaults/metrics/healthCheck
- It already supports 4 balancing strategies: round-robin, random, ip-hash, least-connections
- Performance Hardening Phase 1 added cached selectable upstream snapshots in UpstreamPool
- Earlier local stress test reached ~944 QPS at 128 concurrent clients
- Latest comparable local stress test after Phase 1 reached ~894 QPS at 128 concurrent clients with no errors
- scripts/bench-gateway.sh now defaults to a single 1024-concurrency stress run, supports BENCH_CONCURRENCY overrides, writes per-run CSV summaries, aggregate CSV summaries, raw hey logs, and can run an optional direct-backend baseline
- main request-path integration coverage now exists for ListenerVerticle -> GatewayRouter -> CoreEngine -> UpstreamPool -> HttpProxy -> mock backend

Things I want to brainstorm:
- logging.level is wired into AppLogger runtime filtering
- health semantics are optimistic by default: UNKNOWN and HEALTHY are selectable, UNHEALTHY is excluded; strict mode is opt-in with healthCheck.unknownSelectable=false
- metrics now have JSON and Prometheus text outputs, but they are still backed by basic in-memory snapshots
- benchmark discipline now includes repeated runs and aggregate summaries, but benchmark interpretation and next bottleneck analysis still need care

Please help me:
1. evaluate the current architecture
2. identify 5-10 highest-value refactor or upgrade opportunities
3. propose a short-term and medium-term roadmap
4. propose how to interpret the new repeated benchmark summaries before choosing the next performance optimization
5. challenge my assumptions and point out where I might be optimizing or productizing too early
6. if needed, propose better boundaries between CoreEngine / HttpProxy / RetryPolicy / Health subsystem / Metrics subsystem
```

## 12. Recent Execution Log

### 2026-05-17 - Performance Hardening Phase 1.5

Task completed:

- Implemented benchmark discipline in `scripts/bench-gateway.sh`.
- Kept the phase script-only.
- Preserved existing production Java code.
- Preserved `smoke-gateway.sh`.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `./scripts/bench-gateway.sh`: passed after approved sandbox escalation
- `KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`: passed after approved sandbox escalation
- direct-backend baseline short check: passed
- `git diff --check`: passed

Notes for next execution:

- After each implementation or verification pass, update this file with:
  - task name and date
  - files changed
  - commands run and results
  - benchmark artifact paths, if any
  - deviations or blocked steps

### 2026-05-17 - Single 1024-Concurrency Stress Benchmark

Task completed:

- Updated `scripts/bench-gateway.sh` to default to a single `BENCH_CONCURRENCY=1024`.
- Kept warmup and repeated runs.
- Kept optional direct-backend baseline support.
- Preserved production Java code and `smoke-gateway.sh`.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `./scripts/bench-gateway.sh`: passed
- `KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`: passed
- `DIRECT_BACKEND_BASELINE=true KEEP_BENCH_LOGS=true ./scripts/bench-gateway.sh`: passed after approved sandbox escalation
- `git diff --check`: passed

Artifacts:

- gateway-only retained benchmark logs: `/tmp/tmp.kfF0SIQ9ct`
- gateway + direct-backend retained benchmark logs: `/tmp/tmp.EDudsDewqg`

Deviation:

- The first direct-backend baseline attempt in the sandbox failed because Gradle could not create its wrapper lock file under `~/.gradle`.
- The direct-backend benchmark command was rerun outside the sandbox with approval and completed successfully.

### 2026-05-17 - Config-driven RetryPolicy

Task completed:

- Added `RetryPolicy` under `src/main/java/io/vertilb/engine`.
- Replaced hardcoded retry status checks in `CoreEngine` with policy-driven behavior.
- Wired retry config from `AppConfig.defaults.retries` in `VertiLB`.
- Passed retry-candidate statuses into `HttpProxy` so proxy response deferral matches config.
- Implemented non-blocking retry backoff with `vertx.setTimer(...)`.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `git diff --check`: passed

Scope notes:

- No object pooling was added.
- No benchmark or performance optimization work was done in this phase.

### 2026-05-17 - Main Request Path Integration Tests

Task completed:

- Added `GatewayRequestPathIntegrationTest`.
- Covered the real request path from listener through proxy to mock backend.
- Verified route matching, prefix stripping, query preservation, unknown-route `404`, retry-exhausted `503`, and success metrics recording.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew test --tests '*GatewayRequestPathIntegrationTest'`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `git diff --check`: passed

### 2026-05-17 - Prometheus Metrics Endpoint

Task completed:

- Added `PrometheusMetricsFormatter`.
- Kept existing `/metrics` JSON behavior.
- Added `/metrics/prometheus` as `text/plain; version=0.0.4`.
- Added tests for formatter output, label escaping, JSON metrics response, and Prometheus metrics response.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew --no-daemon test --tests 'io.vertilb.observability.*'`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `git diff --check`: passed

### 2026-05-17 - Strict Health Mode

Task completed:

- Added `healthCheck.unknownSelectable`, default `true`.
- Wired `unknownSelectable=false` into `UpstreamPool` selection.
- Preserved default optimistic behavior.
- Updated `codebase.md` only for documentation.

Important command outcomes:

- `./gradlew clean compileJava`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- `git diff --check`: passed

### 2026-05-17 - Benchmark Summary Aggregation

Task completed:

- Updated `scripts/bench-gateway.sh`.
- Added `benchmark-aggregate.csv`.
- Added `direct-backend-aggregate.csv` when direct backend baseline is enabled.
- Added readable aggregate table output.
- Kept existing per-run CSV output unchanged.

Important command outcomes:

- `bash -n scripts/bench-gateway.sh`: passed
- `./gradlew clean compileJava`: passed
- `./gradlew clean test`: passed
- `./scripts/smoke-gateway.sh`: passed
- short `hey` benchmark with kept logs: passed after approved sandbox escalation
- `git diff --check`: passed

Artifact:

- retained benchmark logs: `/tmp/tmp.MCYWgnLQQv`

Deviation:

- The first sandboxed benchmark attempt failed because Gradle could not write its wrapper lock under `~/.gradle`.
- The command was rerun outside the sandbox with approval and completed successfully.

## 13. Short Conclusion

This is a relatively small codebase, but it already has a solid modular architecture and is easy to extend. The highest-value brainstorming areas right now are:

- making runtime behavior truly config-driven
- clarifying health semantics
- improving observability
- interpreting repeated benchmark data before more performance optimizations
- strengthening integration-test discipline
