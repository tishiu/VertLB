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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

        this.httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setKeepAlive(true)
            .setTcpNoDelay(true)
        );

        for (Upstream upstream : pool.upstreams()) {
            states.put(upstream.id(), new HealthState());
        }

        runProbes()
            .onSuccess(ignored -> {
                schedulePeriodicProbes();
                startPromise.complete();
            })
            .onFailure(error -> {
                schedulePeriodicProbes();
                startPromise.complete();
            });
    }

    @Override
    public void stop() {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }

        if (httpClient != null) {
            httpClient.close();
        }
    }

    private void schedulePeriodicProbes() {
        long intervalMs = intervalMs();

        timerId = vertx.setPeriodic(intervalMs, ignored -> {
            runProbes()
                .onFailure(error -> logger.logError(
                    "Health probe cycle failed for pool=" + pool.name(),
                    error
                ));
        });
    }

    private Future<Void> runProbes() {
        Promise<Void> allDone = Promise.promise();

        if (pool.upstreams().isEmpty()) {
            updatePoolStats();
            allDone.complete();
            return allDone.future();
        }

        int[] remaining = {pool.upstreams().size()};

        for (Upstream upstream : pool.upstreams()) {
            probeOne(upstream).onComplete(ignored -> {
                remaining[0]--;

                if (remaining[0] == 0) {
                    updatePoolStats();
                    allDone.complete();
                }
            });
        }

        return allDone.future();
    }

    private Future<Void> probeOne(Upstream upstream) {
        Promise<Void> promise = Promise.promise();

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
                promise.complete();
            })
            .onSuccess(request -> {
                request.putHeader("Host", upstream.host() + ":" + upstream.port());

                request.send()
                    .onFailure(error -> {
                        handleFailure(upstream, error);
                        promise.complete();
                    })
                    .onSuccess(response -> {
                        if (isExpectedStatus(response.statusCode())) {
                            handleSuccess(upstream);
                        } else {
                            handleFailure(
                                upstream,
                                new IllegalStateException("Unexpected health status: " + response.statusCode())
                            );
                        }

                        response.body()
                            .onComplete(ignored -> promise.complete());
                    });
            });

        return promise.future();
    }

    private void handleSuccess(Upstream upstream) {
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

        return HttpMethod.valueOf(config.method.toUpperCase());
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
