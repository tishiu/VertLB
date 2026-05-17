package io.vertilb.observability;

import java.util.Map;
import java.util.TreeMap;

/**
 * Renders in-memory metric snapshots in Prometheus text exposition format.
 */
public class PrometheusMetricsFormatter {
    public String format(MetricsCollector.MetricsSnapshot snapshot) {
        StringBuilder builder = new StringBuilder();

        appendMetric(builder, "vertilb_requests_total", snapshot.totalRequests);
        appendLabeledMetrics(builder, "vertilb_requests_by_pool_total", "pool", snapshot.requestsByPool);
        appendLabeledMetrics(builder, "vertilb_status_code_total", "status", snapshot.statusCodeBuckets);
        appendLabeledMetrics(builder, "vertilb_upstream_requests_total", "upstream", snapshot.upstreamRequestCounts);
        appendLabeledMetrics(builder, "vertilb_errors_total", "category", snapshot.errorCounts);
        appendLatencySummary(builder, snapshot.latencySummary);
        appendPoolStats(builder, snapshot.poolStats);

        return builder.toString();
    }

    private void appendLatencySummary(StringBuilder builder, MetricsCollector.LatencySummary summary) {
        appendMetric(builder, "vertilb_latency_count", summary.count);
        appendMetric(builder, "vertilb_latency_avg_ms", summary.avg);
        appendMetric(builder, "vertilb_latency_p50_ms", summary.p50);
        appendMetric(builder, "vertilb_latency_p95_ms", summary.p95);
        appendMetric(builder, "vertilb_latency_p99_ms", summary.p99);
    }

    private void appendPoolStats(StringBuilder builder,
                                 Map<String, MetricsCollector.PoolStats> poolStats) {
        for (Map.Entry<String, MetricsCollector.PoolStats> entry : sorted(poolStats).entrySet()) {
            MetricsCollector.PoolStats stats = entry.getValue();
            String pool = entry.getKey();

            appendLabeledMetric(builder, "vertilb_pool_upstreams_total", "pool", pool, stats.totalUpstreams);
            appendLabeledMetric(builder, "vertilb_pool_upstreams_healthy", "pool", pool, stats.healthyUpstreams);
            appendLabeledMetric(builder, "vertilb_pool_upstreams_unhealthy", "pool", pool, stats.unhealthyUpstreams);
            appendLabeledMetric(builder, "vertilb_pool_upstreams_unknown", "pool", pool, stats.unknownUpstreams);
        }
    }

    private void appendLabeledMetrics(StringBuilder builder,
                                      String metricName,
                                      String labelName,
                                      Map<String, Long> values) {
        for (Map.Entry<String, Long> entry : sorted(values).entrySet()) {
            appendLabeledMetric(builder, metricName, labelName, entry.getKey(), entry.getValue());
        }
    }

    private void appendMetric(StringBuilder builder, String metricName, long value) {
        builder.append(metricName)
            .append(' ')
            .append(value)
            .append('\n');
    }

    private void appendMetric(StringBuilder builder, String metricName, double value) {
        builder.append(metricName)
            .append(' ')
            .append(value)
            .append('\n');
    }

    private void appendLabeledMetric(StringBuilder builder,
                                     String metricName,
                                     String labelName,
                                     String labelValue,
                                     long value) {
        builder.append(metricName)
            .append('{')
            .append(labelName)
            .append("=\"")
            .append(escapeLabelValue(labelValue))
            .append("\"} ")
            .append(value)
            .append('\n');
    }

    private <T> Map<String, T> sorted(Map<String, T> values) {
        return new TreeMap<>(values);
    }

    private String escapeLabelValue(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }
}
