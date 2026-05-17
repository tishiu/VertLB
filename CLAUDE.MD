# VertiLB — Java Vert.x Scaffold Guide

> This file is the source of truth for Claude Code agents working on this project.
> Goal: scaffold a production-grade Java Vert.x load balancer (VertiLB) that mirrors the behavior
> described in the JS-LoadBalancer reference implementation, with all known caveats fixed.

---

## 0. Project Identity

| Property      | Value                          |
|---------------|--------------------------------|
| Name          | `vertilb`                      |
| Language      | Java 21                        |
| Build tool    | Gradle 8.x with Kotlin DSL     |
| Runtime       | Vert.x 4.5.x                   |
| Config format | JSON (YAML optional via dep)   |
| Test framework| JUnit 5 + Mockito              |

---

## 1. Scaffold Task (Current Phase)

**What Claude Code must do now:**
1. Generate the full Gradle project layout (files + directories).
2. Write every source file as a **skeleton** — correct package, class name, fields, constructor
   signature, method stubs with `// TODO` bodies, and Javadoc that describes the contract.
3. Do NOT implement business logic yet. Method bodies return `null`, `0`, or throw
   `UnsupportedOperationException("TODO")` as appropriate.
4. All files must compile cleanly with `./gradlew build` (no logic errors, no missing imports).

---

## 2. Gradle Project Layout

```
vertilb/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── src/
│   ├── main/
│   │   └── java/
│   │       └── io/vertilb/
│   │           ├── VertiLB.java               # Main entry point
│   │           ├── config/
│   │           │   ├── AppConfig.java          # Root config object
│   │           │   ├── ListenerConfig.java
│   │           │   ├── PoolConfig.java
│   │           │   ├── UpstreamConfig.java
│   │           │   ├── HealthCheckConfig.java
│   │           │   ├── DefaultsConfig.java
│   │           │   ├── RetryConfig.java
│   │           │   ├── LoggingConfig.java
│   │           │   ├── MetricsConfig.java
│   │           │   └── ConfigLoader.java       # Reads + validates + applies defaults
│   │           ├── pool/
│   │           │   ├── Upstream.java           # Runtime upstream with health state
│   │           │   ├── HealthStatus.java       # Enum: UNKNOWN, HEALTHY, UNHEALTHY
│   │           │   ├── UpstreamPool.java       # Holds upstreams + strategy
│   │           │   └── strategy/
│   │           │       ├── BalancingStrategy.java      # Interface
│   │           │       ├── RoundRobinStrategy.java
│   │           │       ├── RandomStrategy.java
│   │           │       ├── LeastConnectionsStrategy.java
│   │           │       ├── IpHashStrategy.java
│   │           │       └── StrategyFactory.java
│   │           ├── engine/
│   │           │   ├── CoreEngine.java         # Route + retry loop
│   │           │   ├── RequestContext.java     # Per-request state bag
│   │           │   └── error/
│   │           │       ├── NoHealthyUpstreamsException.java
│   │           │       ├── UpstreamTimeoutException.java
│   │           │       └── ProxyException.java
│   │           ├── proxy/
│   │           │   └── HttpProxy.java          # Forward request to upstream
│   │           ├── health/
│   │           │   ├── HealthChecker.java      # Periodic probe verticle
│   │           │   └── HealthState.java        # Threshold tracking per upstream
│   │           ├── http/
│   │           │   └── ListenerVerticle.java   # HTTP server + creates RequestContext
│   │           └── observability/
│   │               ├── AppLogger.java          # Structured logger wrapper
│   │               ├── MetricsCollector.java   # In-memory counters
│   │               └── MetricsVerticle.java    # Separate HTTP server for /metrics
│   └── test/
│       └── java/
│           └── io/vertilb/
│               ├── config/
│               │   └── ConfigLoaderTest.java
│               ├── pool/
│               │   ├── UpstreamPoolTest.java
│               │   └── strategy/
│               │       └── RoundRobinStrategyTest.java
│               ├── engine/
│               │   └── CoreEngineTest.java
│               └── health/
│                   └── HealthCheckerTest.java
└── CLAUDE.MD  ← this file
```

---

## 3. Gradle Files to Generate

### `settings.gradle.kts`
```kotlin
rootProject.name = "vertilb"
```

### `build.gradle.kts`
```kotlin
plugins {
    java
    application
}

group = "io.vertilb"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val vertxVersion = "4.5.10"
val junitVersion = "5.10.2"

dependencies {
    implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web-client")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("io.vertx:vertx-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.vertilb.VertiLB")
}

tasks.test {
    useJUnitPlatform()
}
```

---

## 4. Key Class Contracts (Skeleton Specification)

### `VertiLB.java`
- `main(String[] args)` — parse `-c <path>` and `--validate` flags; call `ConfigLoader.load()`;
  deploy `ListenerVerticle` and `MetricsVerticle` via `Vertx.vertx().deployVerticle(...)`.
- Register shutdown hook for SIGTERM/SIGINT that calls `vertx.close()`.

### `ConfigLoader.java`
- `static AppConfig load(String path)` — reads JSON file, deserializes into `AppConfig`,
  validates fields, applies defaults, runs cross-validation.
  Throws `IllegalArgumentException` with a descriptive message on any violation.
- Validations to enforce (fix JS caveats):
  - listener port `1..65535`
  - pool strategy must be one of known enum values
  - upstream protocol must be `http` or `https`
  - upstream weight must be positive if present
  - every listener `poolName` must reference a pool
  - no duplicate listener ports
  - no duplicate pool names
  - no duplicate upstream IDs within a pool
  - metrics port must not equal any listener port

### `Upstream.java`
- Fields: `id`, `host`, `port`, `protocol`, `weight`, `healthStatus` (volatile), `metadata`.
- `healthStatus` initialized to `HealthStatus.UNKNOWN`.
- `isSelectable()` — returns `true` when `healthStatus != UNHEALTHY`.
  `UNKNOWN` and `HEALTHY` upstreams are selectable.

### `UpstreamPool.java`
- `selectUpstream(RequestContext ctx)` — returns `Optional<Upstream>` of a selectable upstream
  chosen by the pool's strategy.
- `getSelectableUpstreams()` — returns cached selectable upstream snapshot.
- `getHealthyUpstreams()` — legacy alias that delegates to `getSelectableUpstreams()`.
- `updateHealthStatus(String upstreamId, HealthStatus status)` — called by HealthChecker.

### `BalancingStrategy.java` (interface)
```java
public interface BalancingStrategy {
    Upstream select(List<Upstream> healthyUpstreams, RequestContext ctx);
    /** Called after a request completes (success or retryable failure). */
    default void onRequestCompleted(Upstream upstream, RequestContext ctx) {}
}
```
> Fix from JS: use `onRequestCompleted` consistently (JS had `onRequestComplete` vs
> `onRequestCompleted` mismatch that broke least-connections decrement).

### `CoreEngine.java`
- `handleRequest(RequestContext ctx)` — retry loop up to `maxAttempts`; on each attempt
  select upstream, forward via `HttpProxy`, call `strategy.onRequestCompleted` on success.
  `finally` block always records metrics and writes access log.
- Retry behavior is delegated to config-driven `RetryPolicy`.
- Retry backoff uses non-blocking `vertx.setTimer(...)`.
- Retryable errors: connection refused, timeout, connection reset.

### `HealthChecker.java`
- Extends `AbstractVerticle` (Vert.x periodic timer).
- `start()` — schedule first probe immediately (fix from JS: JS delayed first probe by
  one full interval causing cold-start 503s).
- Each probe: HTTP GET to `upstream.host:upstream.port + healthCheckPath`; check status
  code in `expectedStatuses`; increment success or failure counter via `HealthState`.
- On threshold reached, call `pool.updateHealthStatus(id, HEALTHY/UNHEALTHY)`.
- **Fix from JS**: use `vertx.createHttpClient()` with correct `https` vs `http` based on
  `upstream.protocol`.

### `HttpProxy.java`
- `forward(RequestContext ctx, Upstream upstream)` — build Vert.x `WebClient` request,
  copy headers, pipe request body, stream response back to client.
- Check retryable status BEFORE writing response headers to client (preserves JS behavior).
- **Fix from JS**: use HTTPS client when `upstream.protocol == https`.

### `MetricsCollector.java`
- Thread-safe counters for: total requests, requests by pool, status code buckets,
  latency samples (bounded ring buffer, max 10 000), upstream request counts, error counts.
- `updatePoolStats(String poolName, PoolStats stats)` — called on health transitions
  (fix from JS: JS only called this at startup, so pool stats became stale).

### `RequestContext.java`
- Fields: `id` (UUID), `poolName`, `startTime`, `attempt`, `clientRequest` (Vert.x
  `HttpServerRequest`), `selectedUpstream`, `durationMs`.

---

## 5. Behavioral Decisions vs JS Reference

| JS behavior | VertiLB decision |
|---|---|
| UNKNOWN upstreams selectable | **Keep** — same behavior |
| First health probe delayed by one interval | **Fix** — probe immediately on start |
| `https` upstream uses `http.request` | **Fix** — use `https` client when protocol=https |
| `onRequestComplete` vs `onRequestCompleted` mismatch | **Fix** — single name in interface |
| Pool stats only updated at startup | **Fix** — update on every health transition |
| `recordHealthChange()` is a no-op | **Fix** — implement in MetricsCollector |
| Active upstream connection count never updated | **Fix** — increment/decrement in HttpProxy |
| Request body not buffered for retry | **Document** — retry safe only for GET/HEAD or bodyless |
| Weights unused | **Document** — validated but not used; implement in phase 2 |
| Log file paths ignored | **Remove config surface** — log to stdout/stderr only |

---

## 6. Default Values to Hard-Code in `ConfigLoader`

| Config | Default |
|---|---|
| `listener.host` | `0.0.0.0` |
| `defaults.timeout` | `30000` ms |
| `defaults.retries.maxAttempts` | `1` |
| `defaults.retries.retryableStatuses` | `[502, 503, 504]` |
| `defaults.retries.backoffMs` | `100` |
| `defaults.logging.level` | `info` |
| `metrics.enabled` | `true` |
| `metrics.port` | `9090` |
| `metrics.path` | `/metrics` |
| `healthCheck.intervalMs` | `10000` |
| `healthCheck.timeoutMs` | `5000` |
| `healthCheck.path` | `/health` |
| `healthCheck.method` | `GET` |
| `healthCheck.expectedStatuses` | `[200]` |
| `healthCheck.successThreshold` | `2` |
| `healthCheck.failureThreshold` | `3` |

---

## 7. Test Skeletons Required

Each test class must have:
- Class-level Javadoc describing what is being tested.
- At least 3 `@Test` method stubs with descriptive names and `// TODO` bodies.
- No passing assertions yet — just `assertNotNull(null)` or empty body is fine.

---

## 8. What Claude Code Must NOT Do in This Phase

- Do not implement any business logic in method bodies.
- Do not add frameworks beyond what is listed in `build.gradle.kts`.
- Do not create Docker or CI files (next phase).
- Do not rename packages or classes without updating this document.

---

## 9. Done Criteria for This Phase

```
./gradlew build
```
Must complete without compilation errors.
Zero tests need to pass — all can be TODO stubs.
All files listed in section 2 must exist with correct package declarations.
