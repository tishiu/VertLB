package io.vertilb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vertilb.config.AppConfig;
import io.vertilb.config.ConfigLoader;
import io.vertilb.config.HealthCheckConfig;
import io.vertilb.config.ListenerConfig;
import io.vertilb.config.RequestContextPoolConfig;
import io.vertilb.config.PoolConfig;
import io.vertilb.config.RetryConfig;
import io.vertilb.config.UpstreamConfig;
import io.vertilb.engine.AllocatingRequestContextFactory;
import io.vertilb.engine.CoreEngine;
import io.vertilb.engine.PooledRequestContextFactory;
import io.vertilb.engine.RequestContextFactory;
import io.vertilb.engine.RequestContextPool;
import io.vertilb.engine.RetryPolicy;
import io.vertilb.gateway.GatewayRouter;
import io.vertilb.health.HealthChecker;
import io.vertilb.http.ListenerVerticle;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import io.vertilb.observability.MetricsVerticle;
import io.vertilb.pool.Upstream;
import io.vertilb.pool.UpstreamPool;
import io.vertilb.pool.strategy.BalancingStrategy;
import io.vertilb.pool.strategy.StrategyFactory;
import io.vertilb.proxy.HttpProxy;
import io.vertx.core.Vertx;

/**
 * VertiLB composition root.
 *
 * This class wires configuration, runtime pools, gateway router,
 * core engine, proxy, observability, and listener verticles.
 */
public final class VertiLB {
    private VertiLB() {
    }

    public static void main(String[] args) {
        CliOptions cliOptions = parseArgs(args);

        if (cliOptions.configPath == null) {
            printUsageAndExit();
            return;
        }

        AppConfig config = ConfigLoader.load(cliOptions.configPath);

        if (cliOptions.validateOnly) {
            System.out.println("Configuration is valid.");
            return;
        }

        Vertx vertx = Vertx.vertx();

        AppLogger logger = new AppLogger(config.defaults.logging.level);
        MetricsCollector metrics = new MetricsCollector();

        GatewayRouter router = new GatewayRouter(config.routes);

        Map<String, UpstreamPool> pools = buildPools(config);

        long timeoutMs = resolveTimeoutMs(config);
        RetryPolicy retryPolicy = buildRetryPolicy(config);
        HttpProxy proxy = new HttpProxy(vertx, timeoutMs, retryPolicy.retryableStatuses());

        CoreEngine engine = new CoreEngine(
            pools,
            proxy,
            logger,
            metrics,
            retryPolicy,
            vertx
        );

        deployListeners(vertx, config, config.listeners, router, engine);
        deployHealthCheckers(vertx, config, pools, logger, metrics);
        deployMetricsVerticle(vertx, config, metrics);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down VertiLB...");
            vertx.close();
        }));
    }

    private static Map<String, UpstreamPool> buildPools(AppConfig config) {
        Map<String, UpstreamPool> pools = new HashMap<>();

        for (PoolConfig poolConfig : config.pools) {
            List<Upstream> upstreams = buildUpstreams(poolConfig.upstreams);
            BalancingStrategy strategy = StrategyFactory.create(poolConfig.strategy);

            UpstreamPool pool = new UpstreamPool(
                poolConfig.name,
                upstreams,
                strategy,
                resolveUnknownSelectable(poolConfig)
            );

            pools.put(poolConfig.name, pool);
        }

        return pools;
    }

    private static boolean resolveUnknownSelectable(PoolConfig poolConfig) {
        if (poolConfig.healthCheck == null || poolConfig.healthCheck.unknownSelectable == null) {
            return true;
        }

        return poolConfig.healthCheck.unknownSelectable;
    }

    private static List<Upstream> buildUpstreams(List<UpstreamConfig> configs) {
        List<Upstream> upstreams = new ArrayList<>();

        for (UpstreamConfig config : configs) {
            Upstream upstream = new Upstream(
                config.id,
                config.host,
                config.port,
                defaultProtocol(config.protocol),
                defaultWeight(config.weight),
                config.metadata
            );

            upstreams.add(upstream);
        }

        return upstreams;
    }

    private static void deployListeners(Vertx vertx,
                                        AppConfig config,
                                        List<ListenerConfig> listeners,
                                        GatewayRouter router,
                                        CoreEngine engine) {
        for (ListenerConfig listener : listeners) {
            RequestContextFactory contextFactory = createRequestContextFactory(config);

            vertx.deployVerticle(new ListenerVerticle(listener, router, engine, contextFactory))
                .onSuccess(id -> System.out.println(
                    "Listener deployed on " + listener.host + ":" + listener.port + " deploymentId=" + id
                ))
                .onFailure(error -> {
                    System.err.println("Failed to deploy listener on "
                        + listener.host + ":" + listener.port);
                    error.printStackTrace();
                });
        }
    }

    private static RequestContextFactory createRequestContextFactory(AppConfig config) {
        RequestContextPoolConfig poolConfig = config.performance.requestContextPool;

        if (!Boolean.TRUE.equals(poolConfig.enabled)) {
            return new AllocatingRequestContextFactory();
        }

        return new PooledRequestContextFactory(new RequestContextPool(poolConfig.maxSize));
    }

    private static void deployHealthCheckers(Vertx vertx,
                                             AppConfig config,
                                             Map<String, UpstreamPool> pools,
                                             AppLogger logger,
                                             MetricsCollector metrics) {
        for (PoolConfig poolConfig : config.pools) {
            HealthCheckConfig healthCheck = poolConfig.healthCheck;

            if (healthCheck == null || !Boolean.TRUE.equals(healthCheck.enabled)) {
                continue;
            }

            UpstreamPool pool = pools.get(poolConfig.name);

            if (pool == null) {
                continue;
            }

            vertx.deployVerticle(new HealthChecker(pool, healthCheck, logger, metrics))
                .onSuccess(id -> System.out.println(
                    "HealthChecker deployed for pool=" + poolConfig.name + " deploymentId=" + id
                ))
                .onFailure(error -> {
                    System.err.println("Failed to deploy HealthChecker for pool=" + poolConfig.name);
                    error.printStackTrace();
                });
        }
    }

    private static void deployMetricsVerticle(Vertx vertx,
                                              AppConfig config,
                                              MetricsCollector metrics) {
        vertx.deployVerticle(new MetricsVerticle(config.metrics, metrics))
            .onSuccess(id -> {
                if (config.metrics != null && Boolean.TRUE.equals(config.metrics.enabled)) {
                    System.out.println("MetricsVerticle deployed deploymentId=" + id);
                }
            })
            .onFailure(error -> {
                System.err.println("Failed to deploy MetricsVerticle");
                error.printStackTrace();
            });
    }

    private static RetryPolicy buildRetryPolicy(AppConfig config) {
        RetryConfig retryConfig = config.defaults.retries;

        return new RetryPolicy(
            retryConfig.maxAttempts,
            new HashSet<>(retryConfig.retryableStatuses),
            retryConfig.backoffMs
        );
    }

    private static long resolveTimeoutMs(AppConfig config) {
        if (config.defaults == null || config.defaults.timeout == null) {
            return 30_000L;
        }

        return config.defaults.timeout.longValue();
    }

    private static String defaultProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "http";
        }

        return protocol;
    }

    private static int defaultWeight(Integer weight) {
        if (weight == null || weight <= 0) {
            return 1;
        }

        return weight;
    }

    private static CliOptions parseArgs(String[] args) {
        CliOptions options = new CliOptions();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("-c".equals(arg) || "--config".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing config path after " + arg);
                }

                options.configPath = args[++i];
                continue;
            }

            if ("--validate".equals(arg)) {
                options.validateOnly = true;
            }
        }

        return options;
    }

    private static void printUsageAndExit() {
        System.err.println("Usage: VertiLB -c <config-path> [--validate]");
        System.exit(1);
    }

    private static final class CliOptions {
        private String configPath;
        private boolean validateOnly;
    }
}
