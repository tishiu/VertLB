package io.vertilb.engine;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.vertilb.engine.error.NoHealthyUpstreamsException;
import io.vertilb.engine.error.UpstreamTimeoutException;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import io.vertilb.pool.Upstream;
import io.vertilb.pool.UpstreamPool;
import io.vertilb.proxy.HttpProxy;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Core request engine that selects upstreams, drives retry attempts,
 * records metrics, and writes logs.
 */
public class CoreEngine {
    private final Map<String, UpstreamPool> pools;
    private final HttpProxy proxy;
    private final AppLogger logger;
    private final MetricsCollector metrics;
    private final RetryPolicy retryPolicy;
    private final Vertx vertx;

    /**
     * Creates the core request orchestration engine.
     *
     * @param pools pool registry keyed by pool name
     * @param proxy outbound proxy component
     * @param logger access/error logger
     * @param metrics metrics collector
     * @param retryPolicy retry configuration
     */
    public CoreEngine(Map<String, UpstreamPool> pools,
                      HttpProxy proxy,
                      AppLogger logger,
                      MetricsCollector metrics,
                      RetryPolicy retryPolicy) {
        this(pools, proxy, logger, metrics, retryPolicy, null);
    }

    public CoreEngine(Map<String, UpstreamPool> pools,
                      HttpProxy proxy,
                      AppLogger logger,
                      MetricsCollector metrics,
                      RetryPolicy retryPolicy,
                      Vertx vertx) {
        this.pools = pools;
        this.proxy = proxy;
        this.logger = logger;
        this.metrics = metrics;
        this.retryPolicy = retryPolicy;
        this.vertx = vertx;
    }

    /**
     * Main request pipeline.
     *
     * Flow:
     * 1. Look up pool by ctx.poolName
     * 2. Select upstream from pool
     * 3. Forward through HttpProxy
     * 4. Retry if allowed
     * 5. Map final error
     * 6. Observe with logger and metrics
     *
     * @param ctx request context
     * @return future completed when request handling finishes
     */
    public Future<Void> handleRequest(RequestContext ctx) {
        UpstreamPool pool = pools.get(ctx.poolName);

        if (pool == null) {
            IllegalArgumentException error =
                new IllegalArgumentException("Pool not found: " + ctx.poolName);

            ctx.lastError = error;
            sendErrorResponse(ctx, 500, "Pool not found");
            finishRequest(ctx);

            return Future.failedFuture(error);
        }

        Promise<Void> promise = Promise.promise();
        attemptRequest(ctx, pool, promise);
        return promise.future();
    }

    private void attemptRequest(RequestContext ctx, UpstreamPool pool, Promise<Void> promise) {
        ctx.attemptCount++;

        Optional<Upstream> selected = pool.selectUpstream(ctx);
        if (selected.isEmpty()) {
            NoHealthyUpstreamsException error =
                new NoHealthyUpstreamsException("No selectable upstreams in pool: " + ctx.poolName);

            ctx.lastError = error;
            sendErrorResponse(ctx, 503, "No healthy upstreams");
            finishRequest(ctx);
            promise.fail(error);
            return;
        }

        Upstream upstream = selected.get();
        ctx.selectedUpstreamId = upstream.id();

        proxy.forward(ctx, upstream)
            .onSuccess(ignored -> {
                pool.onRequestCompleted(upstream, ctx);
                finishRequest(ctx);
                promise.complete();
            })
            .onFailure(error -> {
                ctx.lastError = error;
                pool.onRequestCompleted(upstream, ctx);

                if (shouldRetry(ctx)) {
                    retryRequest(ctx, pool, promise);
                    return;
                }

                int status = statusForFailure(ctx);
                sendErrorResponse(ctx, status, "Bad Gateway");
                finishRequest(ctx);
                promise.fail(error);
            });
    }

    private boolean shouldRetry(RequestContext ctx) {
        return retryPolicy.shouldRetry(ctx);
    }

    private void retryRequest(RequestContext ctx, UpstreamPool pool, Promise<Void> promise) {
        if (retryPolicy.backoffMs() <= 0 || vertx == null) {
            attemptRequest(ctx, pool, promise);
            return;
        }

        vertx.setTimer(retryPolicy.backoffMs(), ignored -> attemptRequest(ctx, pool, promise));
    }

    private int statusForFailure(RequestContext ctx) {
        if (ctx.lastError instanceof UpstreamTimeoutException) {
            return 504;
        }

        if (ctx.responseStatusCode > 0) {
            return ctx.responseStatusCode;
        }

        return 502;
    }

    private void sendErrorResponse(RequestContext ctx, int status, String message) {
        ctx.responseStatusCode = status;

        if (ctx.clientRequest == null) {
            return;
        }

        if (ctx.response().ended()) {
            return;
        }

        ctx.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "text/plain")
            .end(message);
    }

    private void finishRequest(RequestContext ctx) {
        ctx.durationMs = System.currentTimeMillis() - ctx.startTime;

        try {
            logger.logAccess(ctx);
            metrics.recordRequest(ctx);

            if (ctx.lastError != null) {
                metrics.recordError("request", ctx.lastError);
            }
        } catch (Exception ignored) {
            // Observability must not break request handling.
            logger.logError("Failed to log or record metrics for request", ignored);
        }
    }
}
