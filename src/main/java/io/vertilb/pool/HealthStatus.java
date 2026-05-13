package io.vertilb.pool;

/**
 * Health state assigned to a runtime upstream by the health checker.
 */
public enum HealthStatus {
    UNKNOWN,
    HEALTHY,
    UNHEALTHY
}
