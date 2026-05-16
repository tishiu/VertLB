package io.vertilb.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vertilb.engine.RequestContext;

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
        if (poolName == null || stats == null) {
            return;
        }

        poolStats.put(poolName, stats.copy());
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    /**
     * Full immutable/copy snapshot for MetricsVerticle.
     */
    public MetricsSnapshot snapshot() {
        return new MetricsSnapshot(
            totalRequests.get(),
            copyAtomicMap(requestsByPool),
            copyAtomicMap(statusCodeBuckets),
            new ArrayList<>(latencySamples),
            copyAtomicMap(upstreamRequestCounts),
            copyAtomicMap(errorCounts),
            copyPoolStatsMap(poolStats)
        );
    }

    public Map<String, Long> requestsByPoolSnapshot() {
        return copyAtomicMap(requestsByPool);
    }

    public Map<String, Long> statusCodeBucketsSnapshot() {
        return copyAtomicMap(statusCodeBuckets);
    }

    public Map<String, Long> upstreamRequestCountsSnapshot() {
        return copyAtomicMap(upstreamRequestCounts);
    }

    public Map<String, Long> errorCountsSnapshot() {
        return copyAtomicMap(errorCounts);
    }

    public Map<String, PoolStats> poolStatsSnapshot() {
        return copyPoolStatsMap(poolStats);
    }

    public List<Long> latencySamplesSnapshot() {
        return new ArrayList<>(latencySamples);
    }

    /**
     * Keep this only for compatibility if old code already uses it.
     * Prefer poolStatsSnapshot() for read-only external access.
     */
    public Map<String, PoolStats> poolStats() {
        return poolStatsSnapshot();
    }

    /**
     * Keep this only for compatibility if old code already uses it.
     * Prefer latencySamplesSnapshot().
     */
    public Queue<Long> latencySamples() {
        return new ConcurrentLinkedQueue<>(latencySamples);
    }

    private void increment(ConcurrentMap<String, AtomicLong> map, String key) {
        map.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private Map<String, Long> copyAtomicMap(ConcurrentMap<String, AtomicLong> source) {
        Map<String, Long> copy = new ConcurrentHashMap<>();

        for (Map.Entry<String, AtomicLong> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().get());
        }

        return Map.copyOf(copy);
    }

    private Map<String, PoolStats> copyPoolStatsMap(ConcurrentMap<String, PoolStats> source) {
        Map<String, PoolStats> copy = new ConcurrentHashMap<>();

        for (Map.Entry<String, PoolStats> entry : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }

        return Map.copyOf(copy);
    }

    /**
     * Snapshot of all metrics exposed to MetricsVerticle.
     */
    public static class MetricsSnapshot {
        public final long totalRequests;
        public final Map<String, Long> requestsByPool;
        public final Map<String, Long> statusCodeBuckets;
        public final List<Long> latencySamples;
        public final Map<String, Long> upstreamRequestCounts;
        public final Map<String, Long> errorCounts;
        public final Map<String, PoolStats> poolStats;

        public MetricsSnapshot(long totalRequests,
                               Map<String, Long> requestsByPool,
                               Map<String, Long> statusCodeBuckets,
                               List<Long> latencySamples,
                               Map<String, Long> upstreamRequestCounts,
                               Map<String, Long> errorCounts,
                               Map<String, PoolStats> poolStats) {
            this.totalRequests = totalRequests;
            this.requestsByPool = requestsByPool;
            this.statusCodeBuckets = statusCodeBuckets;
            this.latencySamples = List.copyOf(latencySamples);
            this.upstreamRequestCounts = upstreamRequestCounts;
            this.errorCounts = errorCounts;
            this.poolStats = poolStats;
        }
    }

    /**
     * Snapshot of health counts for one upstream pool.
     */
    public static class PoolStats {
        public int totalUpstreams;
        public int healthyUpstreams;
        public int unhealthyUpstreams;
        public int unknownUpstreams;

        public PoolStats copy() {
            PoolStats copy = new PoolStats();
            copy.totalUpstreams = totalUpstreams;
            copy.healthyUpstreams = healthyUpstreams;
            copy.unhealthyUpstreams = unhealthyUpstreams;
            copy.unknownUpstreams = unknownUpstreams;
            return copy;
        }
    }
}