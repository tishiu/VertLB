package io.vertilb.config;

import java.util.List;

/**
 * Root configuration object containing listeners, gateway routes, upstream pools,
 * defaults, and metrics settings.
 */
public class AppConfig {
    public List<ListenerConfig> listeners;
    public List<RouteConfig> routes;
    public List<PoolConfig> pools;
    public DefaultsConfig defaults;
    public MetricsConfig metrics;
    public PerformanceConfig performance;
}
