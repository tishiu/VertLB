package io.vertilb.config;

/**
 * Configuration object for global timeout, retry, logging, and metrics defaults.
 */
public class DefaultsConfig {
    public Integer timeout;
    public RetryConfig retries;
    public LoggingConfig logging;
    public MetricsConfig metrics;
}
