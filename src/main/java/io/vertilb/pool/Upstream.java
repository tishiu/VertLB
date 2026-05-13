package io.vertilb.pool;

import java.util.Map;

/**
 * Runtime upstream endpoint that owns immutable endpoint data and mutable health state.
 */
public class Upstream {
    private String id;
    private String host;
    private int port;
    private String protocol;
    private int weight;
    private volatile HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private Map<String, Object> metadata;

    /**
     * Creates an upstream runtime model.
     *
     * @param id stable upstream identifier
     * @param host upstream host name or address
     * @param port upstream port
     * @param protocol upstream protocol, either {@code http} or {@code https}
     * @param weight positive upstream weight
     * @param metadata optional upstream metadata
     */
    public Upstream(String id, String host, int port, String protocol, int weight, Map<String, Object> metadata) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Determines whether this upstream can receive proxied traffic.
     *
     * @return true only when the upstream is healthy
     */
    public boolean isSelectable() {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
