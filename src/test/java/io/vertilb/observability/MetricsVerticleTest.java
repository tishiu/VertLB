package io.vertilb.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertilb.config.MetricsConfig;
import io.vertilb.engine.RequestContext;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class MetricsVerticleTest {
    @Test
    void metricsEndpointReturnsJsonAndIncludesLatencySummary(Vertx vertx,
                                                             VertxTestContext testContext) throws IOException {
        MetricsCollector collector = new MetricsCollector();
        RequestContext ctx = new RequestContext();
        ctx.durationMs = 42;
        collector.recordRequest(ctx);

        MetricsConfig config = new MetricsConfig();
        config.enabled = true;
        config.port = unusedPort();
        config.path = "/metrics";

        vertx.deployVerticle(new MetricsVerticle(config, collector))
            .onFailure(testContext::failNow)
            .onSuccess(deploymentId -> {
                RequestOptions options = new RequestOptions()
                    .setPort(config.port)
                    .setHost("127.0.0.1")
                    .setURI("/metrics");
                HttpClient client = vertx.createHttpClient();

                client.request(options)
                    .compose(request -> request.send())
                    .compose(response -> response.body()
                        .map(body -> new ClientResponse(
                            response.statusCode(),
                            response.getHeader("Content-Type"),
                            body.toString()
                        )))
                    .onFailure(testContext::failNow)
                    .onSuccess(response -> testContext.verify(() -> {
                        JsonObject json = new JsonObject(response.body());
                        JsonObject summary = json.getJsonObject("latencySummary");

                        assertEquals(200, response.statusCode());
                        assertNotNull(response.contentType());
                        assertTrue(response.contentType().startsWith("application/json"));
                        assertTrue(json.containsKey("latencySamples"));
                        assertEquals(1, summary.getInteger("count"));
                        assertEquals(42L, summary.getLong("min"));
                        assertEquals(42L, summary.getLong("max"));
                        assertEquals(42.0, summary.getDouble("avg"));
                        assertEquals(42L, summary.getLong("p50"));
                        assertEquals(42L, summary.getLong("p95"));
                        assertEquals(42L, summary.getLong("p99"));

                        client.close();
                        vertx.undeploy(deploymentId)
                            .onComplete(ignored -> testContext.completeNow());
                    }));
            });
    }

    @Test
    void prometheusEndpointReturnsTextPlain(Vertx vertx, VertxTestContext testContext) throws IOException {
        MetricsCollector collector = new MetricsCollector();
        RequestContext ctx = new RequestContext("user-service", null);
        ctx.responseStatusCode = 200;
        ctx.durationMs = 25;
        collector.recordRequest(ctx);

        MetricsConfig config = new MetricsConfig();
        config.enabled = true;
        config.port = unusedPort();
        config.path = "/metrics";

        vertx.deployVerticle(new MetricsVerticle(config, collector))
            .compose(deploymentId -> get(vertx, config.port, "/metrics/prometheus")
                .compose(response -> vertx.undeploy(deploymentId).map(response)))
            .onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertNotNull(response.contentType());
                assertTrue(response.contentType().startsWith("text/plain"));
                assertTrue(response.body().contains("vertilb_requests_total 1"));
                assertTrue(response.body().contains("vertilb_requests_by_pool_total{pool=\"user-service\"} 1"));
                testContext.completeNow();
            }));
    }

    private Future<ClientResponse> get(Vertx vertx, int port, String uri) {
        HttpClient client = vertx.createHttpClient();
        Promise<ClientResponse> promise = Promise.promise();
        RequestOptions options = new RequestOptions()
            .setPort(port)
            .setHost("127.0.0.1")
            .setURI(uri);

        client.request(options)
            .compose(request -> request.send())
            .compose(response -> response.body()
                .map(body -> new ClientResponse(
                    response.statusCode(),
                    response.getHeader("Content-Type"),
                    body.toString()
                )))
            .onComplete(result -> {
                client.close();

                if (result.succeeded()) {
                    promise.complete(result.result());
                } else {
                    promise.fail(result.cause());
                }
            });

        return promise.future();
    }

    private int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record ClientResponse(int statusCode, String contentType, String body) {
    }
}
