package io.vertilb.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertilb.config.HealthCheckConfig;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import io.vertilb.pool.HealthStatus;
import io.vertilb.pool.Upstream;
import io.vertilb.pool.UpstreamPool;
import io.vertilb.pool.strategy.RoundRobinStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Test skeleton for immediate probing, periodic health checks, and threshold transitions.
 */
@ExtendWith(VertxExtension.class)
class HealthCheckerTest {
    @Test
    void schedulesImmediateProbeOnStart(Vertx vertx, VertxTestContext testContext) {
        vertx.createHttpServer()
            .requestHandler(request -> request.response().setStatusCode(204).end())
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(server -> {
                Upstream upstream = upstream(server.actualPort());
                MetricsCollector metrics = new MetricsCollector();

                vertx.deployVerticle(new HealthChecker(
                    pool(upstream),
                    config(204),
                    new AppLogger(),
                    metrics
                )).onFailure(testContext::failNow)
                    .onSuccess(id -> testContext.verify(() -> {
                        assertEquals(HealthStatus.HEALTHY, upstream.healthStatus());
                        assertPoolStats(metrics, 1, 1, 0, 0);
                        server.close().onComplete(ignored -> testContext.completeNow());
                    }));
            });
    }

    @Test
    void marksUpstreamUnhealthyAfterFailureThreshold(Vertx vertx, VertxTestContext testContext) {
        vertx.createHttpServer()
            .requestHandler(request -> request.response().setStatusCode(500).end())
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(server -> {
                Upstream upstream = upstream(server.actualPort());
                MetricsCollector metrics = new MetricsCollector();

                vertx.deployVerticle(new HealthChecker(
                    pool(upstream),
                    config(200),
                    new AppLogger(),
                    metrics
                )).onFailure(testContext::failNow)
                    .onSuccess(id -> testContext.verify(() -> {
                        assertEquals(HealthStatus.UNHEALTHY, upstream.healthStatus());
                        assertPoolStats(metrics, 1, 0, 1, 0);
                        server.close().onComplete(ignored -> testContext.completeNow());
                    }));
            });
    }

    @Test
    void disabledConfigCompletesWithoutProbing(Vertx vertx, VertxTestContext testContext) {
        Upstream upstream = upstream(1);
        MetricsCollector metrics = new MetricsCollector();
        HealthCheckConfig config = config(200);
        config.enabled = false;

        vertx.deployVerticle(new HealthChecker(
            pool(upstream),
            config,
            new AppLogger(),
            metrics
        )).onFailure(testContext::failNow)
            .onSuccess(id -> testContext.verify(() -> {
                assertEquals(HealthStatus.UNKNOWN, upstream.healthStatus());
                assertEquals(0, metrics.poolStats().size());
                testContext.completeNow();
            }));
    }

    private HealthCheckConfig config(int expectedStatus) {
        HealthCheckConfig config = new HealthCheckConfig();
        config.enabled = true;
        config.intervalMs = 60_000L;
        config.timeoutMs = 500L;
        config.path = "/health";
        config.method = "GET";
        config.expectedStatuses = List.of(expectedStatus);
        config.successThreshold = 1;
        config.failureThreshold = 1;
        return config;
    }

    private Upstream upstream(int port) {
        return new Upstream("upstream-1", "localhost", port, "http", 1, null);
    }

    private UpstreamPool pool(Upstream upstream) {
        return new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy());
    }

    private void assertPoolStats(MetricsCollector metrics,
                                 int total,
                                 int healthy,
                                 int unhealthy,
                                 int unknown) {
        MetricsCollector.PoolStats stats = metrics.poolStats().get("pool");

        assertNotNull(stats);
        assertEquals(total, stats.totalUpstreams);
        assertEquals(healthy, stats.healthyUpstreams);
        assertEquals(unhealthy, stats.unhealthyUpstreams);
        assertEquals(unknown, stats.unknownUpstreams);
    }
}
