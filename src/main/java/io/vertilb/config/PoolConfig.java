package io.vertilb.config;

import java.util.List;

/**
 * Configuration for a named upstream pool, including its balancing strategy and health checks.
 */
public class PoolConfig {
    public String name;
    public String strategy;
    public List<UpstreamConfig> upstreams;
    public HealthCheckConfig healthCheck;
}
