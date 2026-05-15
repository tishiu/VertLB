package io.vertilb.observability;

import io.vertilb.engine.RequestContext;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory collector for request, pool, status, latency, upstream, and error metrics.
 */
public class MetricsCollector {
    private static final int MAX_LATENCY_SAMPLES = 10_000;

    private final AtomicLong totalRequests = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> requestsByPool = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> statusCodeBuckets = new ConcurrentHashMap<>();
    private final Queue<Long> latencySamples = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, AtomicLong> upstreamRequestCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> errorCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PoolStats> poolStats = new ConcurrentHashMap<>();

    public void recordRequest(RequestContext ctx) {
        totalRequests.incrementAndGet();

        if (ctx.poolName != null) {
            increment(requestsByPool, ctx.poolName);
        }

        increment(statusCodeBuckets, String.valueOf(ctx.responseStatusCode));

        if (ctx.selectedUpstreamId != null) {
            increment(upstreamRequestCounts, ctx.selectedUpstreamId);
        }

        if (latencySamples.size() < MAX_LATENCY_SAMPLES) {
            latencySamples.add(ctx.durationMs);
        }
    }

    public void recordError(String category, Throwable error) {
        increment(errorCounts, category == null ? "unknown" : category);
    }

    public void updatePoolStats(String poolName, PoolStats stats) {
        poolStats.put(poolName, stats);
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public ConcurrentMap<String, PoolStats> poolStats() {
        return poolStats;
    }

    public Queue<Long> latencySamples() {
        return latencySamples;
    }

    private void increment(ConcurrentMap<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Snapshot of health counts for one upstream pool.
     */
    public static class PoolStats {
        public int totalUpstreams;
        public int healthyUpstreams;
        public int unhealthyUpstreams;
        public int unknownUpstreams;
    }
}