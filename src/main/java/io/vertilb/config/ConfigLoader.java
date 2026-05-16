package io.vertilb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads, validates, cross-validates, and default-fills application configuration from JSON.
 */
public final class ConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_STRATEGIES = Set.of(
        "round-robin",
        "random",
        "least-connections",
        "ip-hash"
    );
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("http", "https");

    private ConfigLoader() {
    }

    /**
     * Reads a configuration file and returns a validated application configuration.
     *
     * @param path path to a JSON configuration file
     * @return validated application configuration
     * @throws IllegalArgumentException when the configuration is missing or invalid
     */
    public static AppConfig load(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Config path is required");
        }

        try {
            AppConfig config = MAPPER.readValue(Path.of(path).toFile(), AppConfig.class);
            applyDefaults(config);
            validate(config);
            return config;
        } catch (IOException error) {
            throw new IllegalArgumentException("Failed to load config: " + path, error);
        }
    }

    private static void applyDefaults(AppConfig config) {
        if (config.routes == null) {
            config.routes = List.of();
        }

        if (config.defaults == null) {
            config.defaults = new DefaultsConfig();
        }

        if (config.defaults.timeout == null) {
            config.defaults.timeout = 30_000;
        }

        if (config.defaults.retries == null) {
            config.defaults.retries = new RetryConfig();
        }

        if (config.defaults.retries.maxAttempts == null) {
            config.defaults.retries.maxAttempts = 1;
        }

        if (config.defaults.retries.retryableStatuses == null) {
            config.defaults.retries.retryableStatuses = List.of(502, 503, 504);
        }

        if (config.defaults.retries.backoffMs == null) {
            config.defaults.retries.backoffMs = 100L;
        }

        if (config.defaults.logging == null) {
            config.defaults.logging = new LoggingConfig();
        }

        if (config.defaults.logging.level == null || config.defaults.logging.level.isBlank()) {
            config.defaults.logging.level = "info";
        }

        if (config.metrics == null) {
            config.metrics = config.defaults.metrics == null ? new MetricsConfig() : config.defaults.metrics;
        }

        if (config.metrics.enabled == null) {
            config.metrics.enabled = true;
        }

        if (config.metrics.port == null) {
            config.metrics.port = 9090;
        }

        if (config.metrics.path == null || config.metrics.path.isBlank()) {
            config.metrics.path = "/metrics";
        }

        if (config.listeners != null) {
            for (ListenerConfig listener : config.listeners) {
                if (listener != null && (listener.host == null || listener.host.isBlank())) {
                    listener.host = "0.0.0.0";
                }
            }
        }

        if (config.pools != null) {
            for (PoolConfig pool : config.pools) {
                applyPoolDefaults(pool);
            }
        }
    }

    private static void applyPoolDefaults(PoolConfig pool) {
        if (pool == null) {
            return;
        }

        if (pool.strategy == null || pool.strategy.isBlank()) {
            pool.strategy = "round-robin";
        }

        if (pool.upstreams != null) {
            for (UpstreamConfig upstream : pool.upstreams) {
                if (upstream != null && (upstream.protocol == null || upstream.protocol.isBlank())) {
                    upstream.protocol = "http";
                }
            }
        }

        if (pool.healthCheck == null) {
            pool.healthCheck = new HealthCheckConfig();
        }

        if (pool.healthCheck.enabled == null) {
            pool.healthCheck.enabled = true;
        }

        if (pool.healthCheck.intervalMs == null) {
            pool.healthCheck.intervalMs = 10_000L;
        }

        if (pool.healthCheck.timeoutMs == null) {
            pool.healthCheck.timeoutMs = 5_000L;
        }

        if (pool.healthCheck.path == null || pool.healthCheck.path.isBlank()) {
            pool.healthCheck.path = "/health";
        }

        if (pool.healthCheck.method == null || pool.healthCheck.method.isBlank()) {
            pool.healthCheck.method = "GET";
        }

        if (pool.healthCheck.expectedStatuses == null) {
            pool.healthCheck.expectedStatuses = List.of(200);
        }

        if (pool.healthCheck.successThreshold == null) {
            pool.healthCheck.successThreshold = 2;
        }

        if (pool.healthCheck.failureThreshold == null) {
            pool.healthCheck.failureThreshold = 3;
        }
    }

    private static void validate(AppConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config is required");
        }

        Set<Integer> listenerPorts = validateListeners(config.listeners);
        Set<String> poolNames = validatePools(config.pools);
        validateRoutes(config.routes, poolNames);
        validateMetrics(config.metrics, listenerPorts);
    }

    private static Set<Integer> validateListeners(List<ListenerConfig> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            throw new IllegalArgumentException("At least one listener is required");
        }

        Set<Integer> listenerPorts = new HashSet<>();
        for (ListenerConfig listener : listeners) {
            if (listener == null) {
                throw new IllegalArgumentException("Listener is required");
            }

            validatePort(listener.port, "Listener port");

            if (!listenerPorts.add(listener.port)) {
                throw new IllegalArgumentException("Duplicate listener port: " + listener.port);
            }
        }

        return listenerPorts;
    }

    private static Set<String> validatePools(List<PoolConfig> pools) {
        if (pools == null || pools.isEmpty()) {
            throw new IllegalArgumentException("At least one pool is required");
        }

        Set<String> poolNames = new HashSet<>();
        for (PoolConfig pool : pools) {
            if (pool == null) {
                throw new IllegalArgumentException("Pool is required");
            }

            if (pool.name == null || pool.name.isBlank()) {
                throw new IllegalArgumentException("Pool name is required");
            }

            if (!poolNames.add(pool.name)) {
                throw new IllegalArgumentException("Duplicate pool name: " + pool.name);
            }

            String strategy = pool.strategy.toLowerCase(Locale.ROOT);
            if (!SUPPORTED_STRATEGIES.contains(strategy)) {
                throw new IllegalArgumentException("Unsupported pool strategy: " + pool.strategy);
            }

            validateUpstreams(pool);
        }

        return poolNames;
    }

    private static void validateUpstreams(PoolConfig pool) {
        if (pool.upstreams == null || pool.upstreams.isEmpty()) {
            throw new IllegalArgumentException("Pool must have at least one upstream: " + pool.name);
        }

        Set<String> upstreamIds = new HashSet<>();
        for (UpstreamConfig upstream : pool.upstreams) {
            if (upstream == null) {
                throw new IllegalArgumentException("Upstream is required");
            }

            if (upstream.id == null || upstream.id.isBlank()) {
                throw new IllegalArgumentException("Upstream id is required");
            }

            if (!upstreamIds.add(upstream.id)) {
                throw new IllegalArgumentException("Duplicate upstream id in pool " + pool.name + ": " + upstream.id);
            }

            if (upstream.host == null || upstream.host.isBlank()) {
                throw new IllegalArgumentException("Upstream host is required: " + upstream.id);
            }

            validatePort(upstream.port, "Upstream port");

            String protocol = upstream.protocol.toLowerCase(Locale.ROOT);
            if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
                throw new IllegalArgumentException("Unsupported upstream protocol: " + upstream.protocol);
            }

            if (upstream.weight != null && upstream.weight <= 0) {
                throw new IllegalArgumentException("Upstream weight must be positive: " + upstream.id);
            }
        }
    }

    private static void validateRoutes(List<RouteConfig> routes, Set<String> poolNames) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("At least one route is required");
        }

        for (RouteConfig route : routes) {
            if (route == null) {
                throw new IllegalArgumentException("Route is required");
            }

            if (route.pathPrefix == null || route.pathPrefix.isBlank()) {
                throw new IllegalArgumentException("Route pathPrefix is required");
            }

            if (route.poolName == null || route.poolName.isBlank()) {
                throw new IllegalArgumentException("Route poolName is required");
            }

            if (!poolNames.contains(route.poolName)) {
                throw new IllegalArgumentException("Route references unknown pool: " + route.poolName);
            }
        }
    }

    private static void validateMetrics(MetricsConfig metrics, Set<Integer> listenerPorts) {
        if (Boolean.FALSE.equals(metrics.enabled)) {
            return;
        }

        validatePort(metrics.port, "Metrics port");

        if (listenerPorts.contains(metrics.port)) {
            throw new IllegalArgumentException("Metrics port must not equal a listener port: " + metrics.port);
        }
    }

    private static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535");
        }
    }
}
