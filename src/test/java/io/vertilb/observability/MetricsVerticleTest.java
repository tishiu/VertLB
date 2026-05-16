package io.vertilb.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void metricsEndpointIncludesLatencySummary(Vertx vertx, VertxTestContext testContext) throws IOException {
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
                    .compose(response -> response.body())
                    .onFailure(testContext::failNow)
                    .onSuccess(body -> testContext.verify(() -> {
                        JsonObject json = body.toJsonObject();
                        JsonObject summary = json.getJsonObject("latencySummary");

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

    private int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
