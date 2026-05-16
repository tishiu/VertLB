package io.vertilb.health;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertilb.config.HealthCheckConfig;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import io.vertilb.pool.HealthStatus;
import io.vertilb.pool.Upstream;
import io.vertilb.pool.UpstreamPool;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vert.x verticle for immediate and periodic upstream health probes.
 */
public class HealthChecker extends AbstractVerticle {
    private final UpstreamPool pool;
    private final HealthCheckConfig config;
    private final AppLogger logger;
    private final MetricsCollector metrics;
    private final Map<String, HealthState> states = new ConcurrentHashMap<>();

    private HttpClient httpClient;
    private long timerId = -1;
    private boolean probeCycleInProgress;
    private boolean stopped;

    public HealthChecker(UpstreamPool pool,
                         HealthCheckConfig config,
                         AppLogger logger,
                         MetricsCollector metrics) {
        this.pool = pool;
        this.config = config;
        this.logger = logger;
        this.metrics = metrics;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        if (config == null || !Boolean.TRUE.equals(config.enabled)) {
            startPromise.complete();
            return;
        }

        stopped = false;
        this.httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setKeepAlive(true)
            .setTcpNoDelay(true)
        );

        for (Upstream upstream : pool.upstreams()) {
            states.put(upstream.id(), new HealthState());
        }

        runProbes()
            .onComplete(ignored -> {
                if (!stopped) {
                    schedulePeriodicProbes();
                }

                startPromise.complete();
            });
    }

    @Override
    public void stop() {
        stopped = true;
        probeCycleInProgress = false;

        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }

        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
    }

    private void schedulePeriodicProbes() {
        long intervalMs = intervalMs();

        timerId = vertx.setPeriodic(intervalMs, ignored -> runScheduledProbeCycle());
    }

    private void runScheduledProbeCycle() {
        if (stopped || probeCycleInProgress) {
            return;
        }

        probeCycleInProgress = true;

        runProbes()
            .onComplete(result -> {
                probeCycleInProgress = false;

                if (result.failed()) {
                    logger.logError(
                        "Health probe cycle failed for pool=" + pool.name(),
                        result.cause()
                    );
                }
            });
    }

    private Future<Void> runProbes() {
        Promise<Void> allDone = Promise.promise();
        List<Upstream> upstreams = pool.upstreams();

        if (stopped) {
            allDone.tryComplete();
            return allDone.future();
        }

        if (upstreams.isEmpty()) {
            updatePoolStats();
            allDone.tryComplete();
            return allDone.future();
        }

        AtomicInteger remaining = new AtomicInteger(upstreams.size());

        for (Upstream upstream : upstreams) {
            Future<Void> probe;

            try {
                probe = probeOne(upstream);
            } catch (RuntimeException error) {
                handleFailure(upstream, error);
                probe = Future.succeededFuture();
            }

            probe.onComplete(ignored -> {
                if (remaining.decrementAndGet() == 0) {
                    updatePoolStats();
                    allDone.tryComplete();
                }
            });
        }

        return allDone.future();
    }

    private Future<Void> probeOne(Upstream upstream) {
        Promise<Void> promise = Promise.promise();

        if (httpClient == null) {
            if (!stopped) {
                handleFailure(upstream, new IllegalStateException("Health checker HTTP client is not started"));
            }

            promise.tryComplete();
            return promise.future();
        }

        RequestOptions options = new RequestOptions()
            .setMethod(method())
            .setHost(upstream.host())
            .setPort(upstream.port())
            .setURI(path())
            .setSsl("https".equalsIgnoreCase(upstream.protocol()))
            .setTimeout(timeoutMs());

        httpClient.request(options)
            .onFailure(error -> {
                handleFailure(upstream, error);
                promise.tryComplete();
            })
            .onSuccess(request -> {
                request.putHeader("Host", upstream.host() + ":" + upstream.port());

                request.send()
                    .onFailure(error -> {
                        handleFailure(upstream, error);
                        promise.tryComplete();
                    })
                    .onSuccess(response -> {
                        boolean expectedStatus = isExpectedStatus(response.statusCode());

                        response.body()
                            .onComplete(body -> {
                                if (body.failed()) {
                                    handleFailure(upstream, body.cause());
                                } else if (expectedStatus) {
                                    handleSuccess(upstream);
                                } else {
                                    handleFailure(
                                        upstream,
                                        new IllegalStateException("Unexpected health status: " + response.statusCode())
                                    );
                                }

                                promise.tryComplete();
                            });
                    });
            });

        return promise.future();
    }

    private void handleSuccess(Upstream upstream) {
        if (stopped) {
            return;
        }

        HealthState state = states.get(upstream.id());

        if (state == null) {
            return;
        }

        boolean thresholdReached = state.recordSuccess(successThreshold());

        if (thresholdReached && upstream.healthStatus() != HealthStatus.HEALTHY) {
            pool.updateHealthStatus(upstream.id(), HealthStatus.HEALTHY);
            logger.logError(
                "Upstream transitioned to HEALTHY: pool=" + pool.name() + " upstream=" + upstream.id(),
                null
            );
        }
    }

    private void handleFailure(Upstream upstream, Throwable error) {
        if (stopped) {
            return;
        }

        HealthState state = states.get(upstream.id());

        if (state == null) {
            return;
        }

        boolean thresholdReached = state.recordFailure(failureThreshold());

        if (thresholdReached && upstream.healthStatus() != HealthStatus.UNHEALTHY) {
            pool.updateHealthStatus(upstream.id(), HealthStatus.UNHEALTHY);
            logger.logError(
                "Upstream transitioned to UNHEALTHY: pool=" + pool.name() + " upstream=" + upstream.id(),
                error
            );
        }
    }

    private boolean isExpectedStatus(int statusCode) {
        if (config.expectedStatuses != null && !config.expectedStatuses.isEmpty()) {
            return config.expectedStatuses.contains(statusCode);
        }

        return statusCode >= 200 && statusCode < 400;
    }

    private void updatePoolStats() {
        MetricsCollector.PoolStats stats = new MetricsCollector.PoolStats();

        for (Upstream upstream : pool.upstreams()) {
            stats.totalUpstreams++;

            if (upstream.healthStatus() == HealthStatus.HEALTHY) {
                stats.healthyUpstreams++;
            } else if (upstream.healthStatus() == HealthStatus.UNHEALTHY) {
                stats.unhealthyUpstreams++;
            } else {
                stats.unknownUpstreams++;
            }
        }

        metrics.updatePoolStats(pool.name(), stats);
    }

    private long intervalMs() {
        if (config.intervalMs == null || config.intervalMs <= 0) {
            return 10_000L;
        }

        return config.intervalMs;
    }

    private long timeoutMs() {
        if (config.timeoutMs == null || config.timeoutMs <= 0) {
            return 5_000L;
        }

        return config.timeoutMs;
    }

    private String path() {
        if (config.path == null || config.path.isBlank()) {
            return "/health";
        }

        return config.path;
    }

    private HttpMethod method() {
        if (config.method == null || config.method.isBlank()) {
            return HttpMethod.GET;
        }

        return HttpMethod.valueOf(config.method.toUpperCase(Locale.ROOT));
    }

    private int successThreshold() {
        if (config.successThreshold == null || config.successThreshold <= 0) {
            return 2;
        }

        return config.successThreshold;
    }

    private int failureThreshold() {
        if (config.failureThreshold == null || config.failureThreshold <= 0) {
            return 3;
        }

        return config.failureThreshold;
    }
}
