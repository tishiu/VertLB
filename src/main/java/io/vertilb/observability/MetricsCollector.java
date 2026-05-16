package io.vertilb.observability;

import java.util.ArrayList;
import java.util.Collections;
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
        List<Long> latencySnapshot = new ArrayList<>(latencySamples);

        return new MetricsSnapshot(
            totalRequests.get(),
            copyAtomicMap(requestsByPool),
            copyAtomicMap(statusCodeBuckets),
            latencySnapshot,
            summarizeLatency(latencySnapshot),
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

    private LatencySummary summarizeLatency(List<Long> samples) {
        if (samples.isEmpty()) {
            return new LatencySummary(0, 0, 0, 0.0, 0, 0, 0);
        }

        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);

        int count = sorted.size();
        long min = sorted.get(0);
        long max = sorted.get(count - 1);
        double avg = sorted.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);

        return new LatencySummary(
            count,
            min,
            max,
            avg,
            percentile(sorted, 0.50),
            percentile(sorted, 0.95),
            percentile(sorted, 0.99)
        );
    }

    private long percentile(List<Long> sorted, double percentile) {
        int count = sorted.size();
        int index = (int) Math.ceil(percentile * count) - 1;
        int clamped = Math.max(0, Math.min(index, count - 1));
        return sorted.get(clamped);
    }

    /**
     * Snapshot of all metrics exposed to MetricsVerticle.
     */
    public static class MetricsSnapshot {
        public final long totalRequests;
        public final Map<String, Long> requestsByPool;
        public final Map<String, Long> statusCodeBuckets;
        public final List<Long> latencySamples;
        public final LatencySummary latencySummary;
        public final Map<String, Long> upstreamRequestCounts;
        public final Map<String, Long> errorCounts;
        public final Map<String, PoolStats> poolStats;

        public MetricsSnapshot(long totalRequests,
                               Map<String, Long> requestsByPool,
                               Map<String, Long> statusCodeBuckets,
                               List<Long> latencySamples,
                               LatencySummary latencySummary,
                               Map<String, Long> upstreamRequestCounts,
                               Map<String, Long> errorCounts,
                               Map<String, PoolStats> poolStats) {
            this.totalRequests = totalRequests;
            this.requestsByPool = requestsByPool;
            this.statusCodeBuckets = statusCodeBuckets;
            this.latencySamples = List.copyOf(latencySamples);
            this.latencySummary = latencySummary;
            this.upstreamRequestCounts = upstreamRequestCounts;
            this.errorCounts = errorCounts;
            this.poolStats = poolStats;
        }
    }

    /**
     * Summary statistics calculated from a copied latency sample list.
     */
    public static class LatencySummary {
        public final int count;
        public final long min;
        public final long max;
        public final double avg;
        public final long p50;
        public final long p95;
        public final long p99;

        public LatencySummary(int count, long min, long max, double avg, long p50, long p95, long p99) {
            this.count = count;
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
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
