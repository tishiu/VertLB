package io.vertilb.pool;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime upstream endpoint that owns immutable endpoint data and mutable health state.
 */
public class Upstream {
    private final String id;
    private final String host;
    private final int port;
    private final String protocol;
    private final int weight;
    private volatile HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private final Map<String, Object> metadata;

    public Upstream(String id,
                    String host,
                    int port,
                    String protocol,
                    int weight,
                    Map<String, Object> metadata) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.protocol = protocol == null || protocol.isBlank() ? "http" : protocol;
        this.weight = weight <= 0 ? 1 : weight;
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    /**
     * UNKNOWN and HEALTHY are selectable.
     * Only UNHEALTHY is excluded.
     */
    public boolean isSelectable() {
        return healthStatus != HealthStatus.UNHEALTHY;
    }

    public String id() {
        return id;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String protocol() {
        return protocol;
    }

    public int weight() {
        return weight;
    }

    public HealthStatus healthStatus() {
        return healthStatus;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public void setHealthStatus(HealthStatus status) {
        this.healthStatus = Objects.requireNonNull(status, "status must not be null");
    }
}