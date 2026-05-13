package io.vertilb.config;

import java.util.List;

/**
 * Root configuration object containing listeners, upstream pools, defaults, and metrics settings.
 */
public class AppConfig {
    public List<ListenerConfig> listeners;
    public List<PoolConfig> pools;
    public DefaultsConfig defaults;
    public MetricsConfig metrics;
}
