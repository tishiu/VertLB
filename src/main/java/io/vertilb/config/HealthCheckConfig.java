package io.vertilb.config;

import java.util.List;

/**
 * Configuration for periodic upstream health probes and threshold-based health transitions.
 */
public class HealthCheckConfig {
    public Boolean enabled;
    public Long intervalMs;
    public Long timeoutMs;
    public String path;
    public String method;
    public List<Integer> expectedStatuses;
    public Integer successThreshold;
    public Integer failureThreshold;
}
