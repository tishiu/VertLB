package io.vertilb.observability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertilb.engine.RequestContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrometheusMetricsFormatterTest {
    @Test
    void formatsRequestPoolStatusUpstreamErrorAndLatencyMetrics() {
        MetricsCollector collector = new MetricsCollector();
        RequestContext ctx = new RequestContext("user-service", null);
        ctx.selectedUpstreamId = "user-1";
        ctx.responseStatusCode = 200;
        ctx.durationMs = 42;
        collector.recordRequest(ctx);
        collector.recordError("request", new RuntimeException("boom"));

        MetricsCollector.PoolStats stats = new MetricsCollector.PoolStats();
        stats.totalUpstreams = 2;
        stats.healthyUpstreams = 1;
        stats.unhealthyUpstreams = 0;
        stats.unknownUpstreams = 1;
        collector.updatePoolStats("user-service", stats);

        String text = new PrometheusMetricsFormatter().format(collector.snapshot());

        assertTrue(text.contains("vertilb_requests_total 1"));
        assertTrue(text.contains("vertilb_requests_by_pool_total{pool=\"user-service\"} 1"));
        assertTrue(text.contains("vertilb_status_code_total{status=\"200\"} 1"));
        assertTrue(text.contains("vertilb_upstream_requests_total{upstream=\"user-1\"} 1"));
        assertTrue(text.contains("vertilb_errors_total{category=\"request\"} 1"));
        assertTrue(text.contains("vertilb_latency_count 1"));
        assertTrue(text.contains("vertilb_latency_avg_ms 42.0"));
        assertTrue(text.contains("vertilb_latency_p50_ms 42"));
        assertTrue(text.contains("vertilb_latency_p95_ms 42"));
        assertTrue(text.contains("vertilb_latency_p99_ms 42"));
        assertTrue(text.contains("vertilb_pool_upstreams_total{pool=\"user-service\"} 2"));
        assertTrue(text.contains("vertilb_pool_upstreams_healthy{pool=\"user-service\"} 1"));
        assertTrue(text.contains("vertilb_pool_upstreams_unhealthy{pool=\"user-service\"} 0"));
        assertTrue(text.contains("vertilb_pool_upstreams_unknown{pool=\"user-service\"} 1"));
    }

    @Test
    void escapesPrometheusLabelValues() {
        MetricsCollector.MetricsSnapshot snapshot = new MetricsCollector.MetricsSnapshot(
            1,
            Map.of("pool\"a\\b\nc", 1L),
            Map.of(),
            java.util.List.of(),
            new MetricsCollector.LatencySummary(0, 0, 0, 0.0, 0, 0, 0),
            Map.of(),
            Map.of(),
            Map.of()
        );

        String text = new PrometheusMetricsFormatter().format(snapshot);

        assertTrue(text.contains("vertilb_requests_by_pool_total{pool=\"pool\\\"a\\\\b\\nc\"} 1"));
    }
}
