# Profiling Analysis

This report analyzes the latest Phase 1 profiling artifacts from `/tmp/tmp.3EDvrXlUgt` and answers whether a `RequestContextPool` is justified.

## 1. Benchmark Summary

- target: `http://localhost:8080/api/users/1?debug=true`
- concurrency: `1024`
- duration: `60s`
- runs: `1`
- QPS: `1282.4211`
- average latency: `792.900 ms`
- p95 latency: `983.000 ms`
- p99 latency: `1073.800 ms`
- status: `ok`

Source artifacts:

- `benchmark-summary.csv`
- `benchmark-aggregate.csv`
- `profile-summary.txt`

## 2. GC Summary

From `gc-summary.txt`:

- pause count: `60`
- total pause time: `380.853 ms`
- max pause time: `19.333 ms`

Pause percentage over the `60s` measured benchmark window:

- `380.853 / 60000 ~= 0.63%`

Interpretation:

- GC does not currently look like a major stop-the-world bottleneck.
- The pause budget is small relative to the benchmark duration.
- The maximum pause is noticeable but not catastrophic for this kind of same-host benchmark.

Additional JFR GC context:

- `jfr-summary.txt` recorded `49` `jdk.GarbageCollection` events and `98` `jdk.GCHeapSummary` events.
- The GC mix is mostly `G1New` with a small number of `G1Old` cycles.
- The sampled JFR GC events do not suggest a runaway old-generation problem.

Conclusion:

- GC is active, but the raw pause data does not support the claim that GC is the dominant throughput limiter in this run.

## 3. JFR Summary

JFR validity:

- valid: `yes`
- evidence: `jfr summary /tmp/tmp.3EDvrXlUgt/vertilb.jfr` succeeded and reported a `60 s` recording

Key event groups available:

- `jdk.ObjectAllocationSample`: `3687`
- `jdk.GarbageCollection`: `49`
- `jdk.GCHeapSummary`: `98`
- `jdk.GCPhasePause`: `54`
- `jdk.ExecutionSample`: `818`
- `jdk.NativeMethodSample`: `2907`

Allocation event availability:

- `jdk.ObjectAllocationInNewTLAB`: `0`
- `jdk.ObjectAllocationOutsideTLAB`: `0`
- `jfr-allocation-events.txt` therefore contains GC and heap events, not exact TLAB allocation event payloads
- sampled allocation data is still available through `jdk.ObjectAllocationSample`

Interpretation:

- The recording is usable.
- Exact TLAB / outside-TLAB event streams are missing in this run.
- Allocation analysis below is based on sampled allocation weights, not exact per-allocation accounting.

## 4. Top Allocation Analysis

### Top sampled allocation classes by estimated allocated weight

These figures come from aggregating `jdk.ObjectAllocationSample` events.

| Rank | Class | Category | Sample Count | Estimated Weight |
|---|---|---|---:|---:|
| 1 | `byte[]` | JDK / raw buffers | 732 | `285.0 MiB` |
| 2 | `java.lang.String` | JDK | 158 | `65.9 MiB` |
| 3 | `io.vertx.core.http.impl.headers.HeadersMultiMap$MapEntry` | Vert.x | 138 | `58.9 MiB` |
| 4 | `java.lang.Object[]` | JDK | 108 | `41.5 MiB` |
| 5 | `java.util.LinkedHashMap$Entry` | JDK | 82 | `35.0 MiB` |
| 6 | `io.netty.handler.codec.DefaultHeaders$HeaderEntry` | Netty | 83 | `33.2 MiB` |
| 7 | `io.netty.channel.DefaultChannelPromise` | Netty | 71 | `25.7 MiB` |
| 8 | `java.util.concurrent.ConcurrentHashMap$Node[]` | JDK | 9 | `24.1 MiB` |
| 9 | `io.vertx.core.http.impl.headers.HeadersMultiMap$MapEntry[]` | Vert.x | 48 | `23.1 MiB` |
| 10 | `io.vertx.core.impl.future.PromiseImpl` | Vert.x | 80 | `22.4 MiB` |

### Top sampled allocation classes by sample count

| Rank | Class | Category | Sample Count | Estimated Weight |
|---|---|---|---:|---:|
| 1 | `byte[]` | JDK / raw buffers | 732 | `285.0 MiB` |
| 2 | `java.lang.String` | JDK | 158 | `65.9 MiB` |
| 3 | `io.vertx.core.http.impl.headers.HeadersMultiMap$MapEntry` | Vert.x | 138 | `58.9 MiB` |
| 4 | `java.lang.Long` | JDK | 138 | `15.0 MiB` |
| 5 | `java.lang.Object[]` | JDK | 108 | `41.5 MiB` |
| 6 | `io.netty.handler.codec.DefaultHeaders$HeaderEntry` | Netty | 83 | `33.2 MiB` |
| 7 | `java.util.LinkedHashMap$Entry` | JDK | 82 | `35.0 MiB` |
| 8 | `io.vertx.core.impl.future.PromiseImpl` | Vert.x | 80 | `22.4 MiB` |
| 9 | `io.netty.channel.DefaultChannelPromise` | Netty | 71 | `25.7 MiB` |
| 10 | `io.vertx.core.http.impl.headers.HeadersMultiMap$MapEntry[]` | Vert.x | 48 | `23.1 MiB` |

### Broad allocation mix

By allocated class family:

- JDK classes: about `430.7 MiB`
- Vert.x classes: about `389.1 MiB`
- Netty classes: about `276.0 MiB`
- VertiLB classes: about `26.2 MiB`
- logging classes: about `9.3 MiB`

By first meaningful stack family:

- Vert.x stack roots: about `541.7 MiB`
- Netty stack roots: about `507.8 MiB`
- JDK stack roots: about `150.1 MiB`
- logging stack roots: about `104.5 MiB`
- VertiLB stack roots: about `90.1 MiB`

### Notable stack-root hotspots

Top non-JDK stack roots by sampled weight:

- `ch.qos.logback.classic.layout.TTLLLayout.doLayout`: about `55.3 MiB`
- `io.vertx.core.http.impl.headers.HeadersMultiMap.add0`: about `54.3 MiB`
- `io.netty.util.internal.PlatformDependent.allocateUninitializedArray`: about `49.9 MiB`
- `io.netty.handler.codec.http.HttpObjectDecoder.langAsciiString`: about `38.5 MiB`
- `io.netty.handler.codec.http.DefaultHttpHeadersFactory.newHeaders`: about `37.1 MiB`
- `io.vertilb.proxy.HttpProxy.isHopByHopHeader`: about `27.3 MiB`

### VertiLB-local classes

Top VertiLB classes in sampled allocation weights:

- `io.vertilb.engine.RequestContext`: `13` samples, about `6.0 MiB`
- several generated lambda classes under `io.vertilb.proxy.HttpProxy` and `io.vertilb.engine.CoreEngine`: roughly `0.5 MiB` to `3.5 MiB` each

Interpretation:

- The strongest recurring runtime signals are header-related data structures, arrays, strings, Netty promises, and Vert.x future / header structures.
- `RequestContext` is present, but it is not one of the dominant allocation classes in this recording.
- `RequestContext` sampled allocation weight is about `0.4%` of the total sampled weight aggregated here.

## 5. RequestContext Decision

Does `RequestContext` appear as a major allocation source?

- no

Why:

- `RequestContext` appears in the sampled allocation stream, but only `13` times for about `6.0 MiB` total sampled weight.
- Much larger signals come from:
  - raw arrays and strings
  - Vert.x header map entries
  - Netty header entries and promises
  - future / callback machinery
  - request logging layout work

Is `RequestContextPool` justified now?

- no

Why not:

- The current profile does not show `RequestContext` as a primary allocation driver.
- GC pause time is low enough that there is no strong evidence that pooling this one application object will materially change the observed behavior.
- The dominant sampled allocation pressure appears to be in framework-level request/header/promise paths, not in one small app-owned context object.

## 6. Optimization Candidates

Ranked from most plausible to least plausible based on this profile:

1. `no optimization yet`
2. `HttpProxy RequestOptions/header copy`
3. `Vert.x/Netty buffer path`
4. `metrics recording`
5. `GatewayRouter route matching / URI rewrite`
6. `RequestContextPool`

Rationale:

- `RequestContextPool` ranks low because the profile does not identify `RequestContext` as a dominant allocator.
- `HttpProxy RequestOptions/header copy` ranks higher because header-related allocations and `HttpProxy.isHopByHopHeader` show up in the runtime stack roots.
- `Vert.x/Netty buffer path` ranks high because arrays, strings, header entries, promises, and Netty/Vert.x structures dominate sampled weights.
- `metrics recording` and `GatewayRouter` are not strong signals in this recording.

## 7. Recommendation

Recommendation:

- `Fix benchmark/profiling method first`

Reason:

- The JFR recording starts before readiness and warmup but runs for only `60s`, while the harness performs `10s` warmup plus a `60s` measured run.
- That means the recording includes startup and warmup work while missing the tail of the measured benchmark window.
- Even with that limitation, the current evidence still does not justify `RequestContextPool`.

If the profiling method is not adjusted first, the next best conclusion would still be:

- `Do not optimize yet; collect better profile`

## 8. Caveats

- This is a same-host benchmark. Scheduler noise, JVM warmup, filesystem state, and local machine load can skew both throughput and latency.
- The backends are mock Python services, so backend behavior is simplified and may not match a real service fleet.
- `jdk.ObjectAllocationSample` is sampled data, not exact accounting. It is good for hotspot direction, not byte-perfect totals.
- `jdk.ObjectAllocationInNewTLAB` and `jdk.ObjectAllocationOutsideTLAB` were unavailable in this run, so exact allocation event streams were not captured.
- The JFR window is not perfectly aligned to the measured benchmark window because recording starts before warmup and startup have finished.

## Conclusion

`RequestContextPool` is not justified by the current evidence.

The profile points first toward header/path overhead in Vert.x/Netty and some request-side logging allocation, while GC pause time stays low enough that GC does not yet look like the main bottleneck. The next step should be to improve profiling alignment so JFR covers the measured benchmark window cleanly before making any pooling or runtime optimization change.
