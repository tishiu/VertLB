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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests immediate probing, health transitions, and disabled health-check behavior.
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

    @Test
    void probeCycleCompletesWhenOneUpstreamFails(Vertx vertx, VertxTestContext testContext) {
        vertx.createHttpServer()
            .requestHandler(request -> request.response().setStatusCode(204).end())
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(healthyServer -> {
                vertx.createHttpServer()
                    .requestHandler(request -> request.response().setStatusCode(500).end())
                    .listen(0)
                    .onFailure(testContext::failNow)
                    .onSuccess(failingServer -> {
                        Upstream healthy = upstream("healthy", healthyServer.actualPort());
                        Upstream failing = upstream("failing", failingServer.actualPort());
                        MetricsCollector metrics = new MetricsCollector();

                        vertx.deployVerticle(new HealthChecker(
                            pool(healthy, failing),
                            config(204),
                            new AppLogger(),
                            metrics
                        )).onFailure(testContext::failNow)
                            .onSuccess(id -> testContext.verify(() -> {
                                assertEquals(HealthStatus.HEALTHY, healthy.healthStatus());
                                assertEquals(HealthStatus.UNHEALTHY, failing.healthStatus());
                                assertPoolStats(metrics, 2, 1, 1, 0);
                                healthyServer.close()
                                    .onComplete(closeHealthy -> failingServer.close()
                                        .onComplete(closeFailing -> testContext.completeNow()));
                            }));
                    });
            });
    }

    @Test
    void updatesUnknownStatsWhenThresholdIsNotReached(Vertx vertx, VertxTestContext testContext) {
        vertx.createHttpServer()
            .requestHandler(request -> request.response().setStatusCode(204).end())
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(server -> {
                Upstream upstream = upstream(server.actualPort());
                MetricsCollector metrics = new MetricsCollector();
                HealthCheckConfig config = config(204);
                config.successThreshold = 2;

                vertx.deployVerticle(new HealthChecker(
                    pool(upstream),
                    config,
                    new AppLogger(),
                    metrics
                )).onFailure(testContext::failNow)
                    .onSuccess(id -> testContext.verify(() -> {
                        assertEquals(HealthStatus.UNKNOWN, upstream.healthStatus());
                        assertPoolStats(metrics, 1, 0, 0, 1);
                        server.close().onComplete(ignored -> testContext.completeNow());
                    }));
            });
    }

    @Test
    void acceptsTwoAndThreeHundredStatusesWhenExpectedStatusesAreEmpty(Vertx vertx,
                                                                        VertxTestContext testContext) {
        vertx.createHttpServer()
            .requestHandler(request -> request.response().setStatusCode(302).end())
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(server -> {
                Upstream upstream = upstream(server.actualPort());
                MetricsCollector metrics = new MetricsCollector();
                HealthCheckConfig config = config(204);
                config.expectedStatuses = List.of();

                vertx.deployVerticle(new HealthChecker(
                    pool(upstream),
                    config,
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
    void skipsPeriodicProbeWhenPreviousCycleIsStillRunning(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger maxActiveRequests = new AtomicInteger();

        vertx.createHttpServer()
            .requestHandler(request -> {
                int active = activeRequests.incrementAndGet();
                maxActiveRequests.updateAndGet(current -> Math.max(current, active));

                vertx.setTimer(150, ignored -> {
                    activeRequests.decrementAndGet();
                    request.response().setStatusCode(204).end();
                });
            })
            .listen(0)
            .onFailure(testContext::failNow)
            .onSuccess(server -> {
                Upstream upstream = upstream(server.actualPort());
                MetricsCollector metrics = new MetricsCollector();
                HealthCheckConfig config = config(204);
                config.intervalMs = 20L;
                config.timeoutMs = 1_000L;

                vertx.deployVerticle(new HealthChecker(
                    pool(upstream),
                    config,
                    new AppLogger(),
                    metrics
                )).onFailure(testContext::failNow)
                    .onSuccess(deploymentId -> vertx.setTimer(350, ignored ->
                        vertx.undeploy(deploymentId)
                            .onFailure(testContext::failNow)
                            .onSuccess(undeployed -> testContext.verify(() -> {
                                assertEquals(1, maxActiveRequests.get());
                                assertPoolStats(metrics, 1, 1, 0, 0);
                                server.close().onComplete(close -> testContext.completeNow());
                            }))
                    ));
            });
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
        return upstream("upstream-1", port);
    }

    private Upstream upstream(String id, int port) {
        return new Upstream(id, "localhost", port, "http", 1, null);
    }

    private UpstreamPool pool(Upstream... upstreams) {
        return new UpstreamPool("pool", List.of(upstreams), new RoundRobinStrategy());
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
