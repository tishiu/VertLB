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
        assertEquals(2, snapshot.poolStats.get("pool").totalUpstreams);
        assertNotSame(stats, snapshot.poolStats.get("pool"));
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
}
