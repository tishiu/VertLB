package io.vertilb.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertilb.engine.RequestContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {
    @Test
    void snapshotReturnsCopiesOfCollectedMetrics() {
        MetricsCollector collector = new MetricsCollector();
        RequestContext ctx = new RequestContext();
        ctx.responseStatusCode = 200;
        ctx.selectedUpstreamId = "upstream-1";
        ctx.durationMs = 25;

        collector.recordRequest(ctx);
        collector.recordError("request", new RuntimeException("boom"));

        MetricsCollector.PoolStats stats = new MetricsCollector.PoolStats();
        stats.totalUpstreams = 2;
        stats.healthyUpstreams = 1;
        stats.unhealthyUpstreams = 1;
        collector.updatePoolStats("pool", stats);

        MetricsCollector.MetricsSnapshot snapshot = collector.snapshot();

        assertEquals(1, snapshot.totalRequests);
        assertEquals(Map.of("200", 1L), snapshot.statusCodeBuckets);
        assertEquals(Map.of("upstream-1", 1L), snapshot.upstreamRequestCounts);
        assertEquals(Map.of("request", 1L), snapshot.errorCounts);
        assertEquals(25, snapshot.latencySamples.get(0));
        assertEquals(1, snapshot.latencySummary.count);
        assertEquals(25, snapshot.latencySummary.min);
        assertEquals(25, snapshot.latencySummary.max);
        assertEquals(25.0, snapshot.latencySummary.avg);
        assertEquals(25, snapshot.latencySummary.p50);
        assertEquals(25, snapshot.latencySummary.p95);
        assertEquals(25, snapshot.latencySummary.p99);
        assertEquals(2, snapshot.poolStats.get("pool").totalUpstreams);
        assertNotSame(stats, snapshot.poolStats.get("pool"));
    }

    @Test
    void emptyLatencySummaryUsesZeroValues() {
        MetricsCollector collector = new MetricsCollector();

        MetricsCollector.LatencySummary summary = collector.snapshot().latencySummary;

        assertEquals(0, summary.count);
        assertEquals(0, summary.min);
        assertEquals(0, summary.max);
        assertEquals(0.0, summary.avg);
        assertEquals(0, summary.p50);
        assertEquals(0, summary.p95);
        assertEquals(0, summary.p99);
    }

    @Test
    void nonEmptyLatencySummaryUsesSortedSnapshotPercentiles() {
        MetricsCollector collector = new MetricsCollector();

        recordLatency(collector, 100);
        recordLatency(collector, 10);
        recordLatency(collector, 50);
        recordLatency(collector, 200);
        recordLatency(collector, 300);

        MetricsCollector.LatencySummary summary = collector.snapshot().latencySummary;

        assertEquals(5, summary.count);
        assertEquals(10, summary.min);
        assertEquals(300, summary.max);
        assertEquals(132.0, summary.avg);
        assertEquals(100, summary.p50);
        assertEquals(300, summary.p95);
        assertEquals(300, summary.p99);
    }

    @Test
    void snapshotMapsAreImmutable() {
        MetricsCollector collector = new MetricsCollector();

        assertThrows(UnsupportedOperationException.class,
            () -> collector.requestsByPoolSnapshot().put("pool", 1L));
        assertThrows(UnsupportedOperationException.class,
            () -> collector.poolStatsSnapshot().put("pool", new MetricsCollector.PoolStats()));
    }

    @Test
    void compatibilityAccessorsReturnCopies() {
        MetricsCollector collector = new MetricsCollector();
        MetricsCollector.PoolStats stats = new MetricsCollector.PoolStats();
        stats.totalUpstreams = 1;
        collector.updatePoolStats("pool", stats);

        stats.totalUpstreams = 99;

        assertEquals(1, collector.poolStats().get("pool").totalUpstreams);
    }

    private void recordLatency(MetricsCollector collector, long durationMs) {
        RequestContext ctx = new RequestContext();
        ctx.durationMs = durationMs;
        collector.recordRequest(ctx);
    }
}
