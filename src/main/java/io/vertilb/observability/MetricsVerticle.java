package io.vertilb.observability;

import java.util.Map;

import io.vertilb.config.MetricsConfig;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Vert.x HTTP verticle that exposes collected metrics on the configured metrics endpoint.
 */
public class MetricsVerticle extends AbstractVerticle {
    private final MetricsConfig config;
    private final MetricsCollector collector;

    public MetricsVerticle(MetricsConfig config, MetricsCollector collector) {
        this.config = config;
        this.collector = collector;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        if (config == null || !Boolean.TRUE.equals(config.enabled)) {
            startPromise.complete();
            return;
        }

        String path = config.path == null || config.path.isBlank()
            ? "/metrics"
            : config.path;

        int port = config.port == null || config.port <= 0
            ? 9100
            : config.port;

        vertx.createHttpServer()
            .requestHandler(request -> {
                if (request.method() != HttpMethod.GET || !path.equals(request.path())) {
                    request.response()
                        .setStatusCode(404)
                        .putHeader("Content-Type", "text/plain")
                        .end("Not Found");
                    return;
                }

                JsonObject json = toJson(collector.snapshot());

                request.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(json.encode());
            })
            .listen(port)
            .onSuccess(server -> startPromise.complete())
            .onFailure(startPromise::fail);
    }

    private JsonObject toJson(MetricsCollector.MetricsSnapshot snapshot) {
        return new JsonObject()
            .put("totalRequests", snapshot.totalRequests)
            .put("requestsByPool", longMapToJson(snapshot.requestsByPool))
            .put("statusCodeBuckets", longMapToJson(snapshot.statusCodeBuckets))
            .put("upstreamRequestCounts", longMapToJson(snapshot.upstreamRequestCounts))
            .put("errorCounts", longMapToJson(snapshot.errorCounts))
            .put("latencySamples", new JsonArray(snapshot.latencySamples))
            .put("poolStats", poolStatsToJson(snapshot.poolStats));
    }

    private JsonObject longMapToJson(Map<String, Long> map) {
        JsonObject json = new JsonObject();

        for (Map.Entry<String, Long> entry : map.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }

        return json;
    }

    private JsonObject poolStatsToJson(Map<String, MetricsCollector.PoolStats> map) {
        JsonObject json = new JsonObject();

        for (Map.Entry<String, MetricsCollector.PoolStats> entry : map.entrySet()) {
            MetricsCollector.PoolStats stats = entry.getValue();

            JsonObject value = new JsonObject()
                .put("totalUpstreams", stats.totalUpstreams)
                .put("healthyUpstreams", stats.healthyUpstreams)
                .put("unhealthyUpstreams", stats.unhealthyUpstreams)
                .put("unknownUpstreams", stats.unknownUpstreams);

            json.put(entry.getKey(), value);
        }

        return json;
    }
}
