package io.vertilb.observability;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    /**
     * Updates the last-known pool health statistics after a health transition.
     *
     * @param poolName pool name
     * @param stats latest pool stats
     */
    public void updatePoolStats(String poolName, PoolStats stats) {
        // TODO
        throw new UnsupportedOperationException("TODO");
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
