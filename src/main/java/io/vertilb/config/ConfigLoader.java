package io.vertilb.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertilb.pool.strategy.StrategyFactory;
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
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("http", "https");
    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of(
        "GET",
        "POST",
        "PUT",
        "PATCH",
        "DELETE",
        "HEAD",
        "OPTIONS",
        "TRACE",
        "CONNECT"
    );
    private static final Set<String> SUPPORTED_LOG_LEVELS = Set.of(
        "ERROR",
        "WARN",
        "INFO",
        "DEBUG",
        "TRACE"
    );

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
        if (config == null) {
            return;
        }

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

        applyRetryDefaults(config.defaults.retries);
        applyLoggingDefaults(config.defaults);

        if (config.metrics == null && config.defaults.metrics != null) {
            config.metrics = config.defaults.metrics;
        }

        applyMetricsDefaults(config.metrics);
        applyListenerDefaults(config.listeners);

        if (config.routes != null) {
            for (RouteConfig route : config.routes) {
                applyRouteDefaults(route);
            }
        }

        if (config.pools != null) {
            for (PoolConfig pool : config.pools) {
                applyPoolDefaults(pool);
            }
        }
    }

    private static void applyRetryDefaults(RetryConfig retries) {
        if (retries.maxAttempts == null) {
            retries.maxAttempts = 3;
        }

        if (retries.retryableStatuses == null) {
            retries.retryableStatuses = List.of(502, 503, 504);
        }

        if (retries.backoffMs == null) {
            retries.backoffMs = 100L;
        }
    }

    private static void applyLoggingDefaults(DefaultsConfig defaults) {
        if (defaults.logging == null) {
            defaults.logging = new LoggingConfig();
        }

        if (defaults.logging.level == null || defaults.logging.level.isBlank()) {
            defaults.logging.level = "INFO";
            return;
        }

        defaults.logging.level = defaults.logging.level.trim().toUpperCase(Locale.ROOT);
    }

    private static void applyMetricsDefaults(MetricsConfig metrics) {
        if (metrics == null || !Boolean.TRUE.equals(metrics.enabled)) {
            return;
        }

        if (metrics.port == null) {
            metrics.port = 9100;
        }

        if (metrics.path == null || metrics.path.isBlank()) {
            metrics.path = "/metrics";
        }
    }

    private static void applyListenerDefaults(List<ListenerConfig> listeners) {
        if (listeners == null) {
            return;
        }

        for (ListenerConfig listener : listeners) {
            if (listener != null && (listener.host == null || listener.host.isBlank())) {
                listener.host = "0.0.0.0";
            }
        }
    }

    private static void applyRouteDefaults(RouteConfig route) {
        if (route == null) {
            return;
        }

        if (route.host != null && route.host.isBlank()) {
            route.host = null;
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
                applyUpstreamDefaults(upstream);
            }
        }

        applyHealthCheckDefaults(pool.healthCheck);
    }

    private static void applyUpstreamDefaults(UpstreamConfig upstream) {
        if (upstream == null) {
            return;
        }

        if (upstream.protocol == null || upstream.protocol.isBlank()) {
            upstream.protocol = "http";
        }

        if (upstream.weight == null || upstream.weight <= 0) {
            upstream.weight = 1;
        }
    }

    private static void applyHealthCheckDefaults(HealthCheckConfig healthCheck) {
        if (healthCheck == null || !Boolean.TRUE.equals(healthCheck.enabled)) {
            return;
        }

        if (healthCheck.intervalMs == null) {
            healthCheck.intervalMs = 10_000L;
        }

        if (healthCheck.timeoutMs == null) {
            healthCheck.timeoutMs = 5_000L;
        }

        if (healthCheck.path == null || healthCheck.path.isBlank()) {
            healthCheck.path = "/health";
        }

        if (healthCheck.method == null || healthCheck.method.isBlank()) {
            healthCheck.method = "GET";
        }

        if (healthCheck.successThreshold == null) {
            healthCheck.successThreshold = 2;
        }

        if (healthCheck.failureThreshold == null) {
            healthCheck.failureThreshold = 3;
        }
    }

    private static void validate(AppConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Config is required");
        }

        validateRetry(config.defaults.retries);
        validateLogging(config.defaults.logging);

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

            if (!route.pathPrefix.startsWith("/")) {
                throw new IllegalArgumentException("Route pathPrefix must start with /");
            }

            if (route.poolName == null || route.poolName.isBlank()) {
                throw new IllegalArgumentException("Route poolName is required");
            }

            if (!poolNames.contains(route.poolName)) {
                throw new IllegalArgumentException("Route references unknown pool: " + route.poolName);
            }

            if (route.methods != null) {
                for (String method : route.methods) {
                    if (!isValidHttpMethod(method)) {
                        throw new IllegalArgumentException("Invalid route method: " + method);
                    }
                }
            }

            validateOptionalPathPrefix(route.stripPrefix, "Route stripPrefix");
            validateOptionalPathPrefix(route.addPrefix, "Route addPrefix");
        }
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

            validateStrategy(pool.strategy);
            validateUpstreams(pool);
            validateHealthCheck(pool.healthCheck);
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
        }
    }

    private static void validateRetry(RetryConfig retries) {
        if (retries.maxAttempts < 1) {
            throw new IllegalArgumentException("Retry maxAttempts must be at least 1");
        }

        if (retries.backoffMs < 0) {
            throw new IllegalArgumentException("Retry backoffMs must be >= 0");
        }

        for (Integer status : retries.retryableStatuses) {
            if (!isValidStatusCode(status)) {
                throw new IllegalArgumentException("Invalid retryable status: " + status);
            }
        }
    }

    private static void validateLogging(LoggingConfig logging) {
        if (logging == null || logging.level == null || logging.level.isBlank()) {
            throw new IllegalArgumentException("Logging level is required");
        }

        if (!SUPPORTED_LOG_LEVELS.contains(logging.level)) {
            throw new IllegalArgumentException("Invalid logging level: " + logging.level);
        }
    }

    private static void validateHealthCheck(HealthCheckConfig healthCheck) {
        if (healthCheck == null || !Boolean.TRUE.equals(healthCheck.enabled)) {
            return;
        }

        if (healthCheck.intervalMs <= 0) {
            throw new IllegalArgumentException("Health check intervalMs must be > 0");
        }

        if (healthCheck.timeoutMs <= 0) {
            throw new IllegalArgumentException("Health check timeoutMs must be > 0");
        }

        if (healthCheck.path == null || healthCheck.path.isBlank()) {
            throw new IllegalArgumentException("Health check path is required");
        }

        if (!healthCheck.path.startsWith("/")) {
            throw new IllegalArgumentException("Health check path must start with /");
        }

        if (!isValidHttpMethod(healthCheck.method)) {
            throw new IllegalArgumentException("Invalid health check method: " + healthCheck.method);
        }

        if (healthCheck.successThreshold <= 0) {
            throw new IllegalArgumentException("Health check successThreshold must be > 0");
        }

        if (healthCheck.failureThreshold <= 0) {
            throw new IllegalArgumentException("Health check failureThreshold must be > 0");
        }

        if (healthCheck.expectedStatuses != null) {
            for (Integer status : healthCheck.expectedStatuses) {
                if (!isValidStatusCode(status)) {
                    throw new IllegalArgumentException("Invalid health check expected status: " + status);
                }
            }
        }
    }

    private static void validateMetrics(MetricsConfig metrics, Set<Integer> listenerPorts) {
        if (metrics == null || !Boolean.TRUE.equals(metrics.enabled)) {
            return;
        }

        validatePort(metrics.port, "Metrics port");

        if (!metrics.path.startsWith("/")) {
            throw new IllegalArgumentException("Metrics path must start with /");
        }

        if (listenerPorts.contains(metrics.port)) {
            throw new IllegalArgumentException("Metrics port must not equal a listener port: " + metrics.port);
        }
    }

    private static void validateStrategy(String strategy) {
        try {
            StrategyFactory.create(strategy);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unsupported pool strategy: " + strategy, error);
        }
    }

    private static void validateOptionalPathPrefix(String value, String fieldName) {
        if (value != null && !value.isBlank() && !value.startsWith("/")) {
            throw new IllegalArgumentException(fieldName + " must start with /");
        }
    }

    private static boolean isValidHttpMethod(String method) {
        return method != null
            && !method.isBlank()
            && SUPPORTED_HTTP_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    private static boolean isValidStatusCode(Integer status) {
        return status != null && status >= 100 && status <= 599;
    }

    private static void validatePort(int port, String fieldName) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 65535");
        }
    }
}
