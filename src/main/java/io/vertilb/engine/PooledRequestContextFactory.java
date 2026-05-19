package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;

public final class PooledRequestContextFactory implements RequestContextFactory {
    private final RequestContextPool pool;

    public PooledRequestContextFactory(RequestContextPool pool) {
        this.pool = pool;
    }

    @Override
    public RequestContext create(String poolName, HttpServerRequest request, String rewrittenUri) {
        return pool.borrow(poolName, request, rewrittenUri);
    }

    @Override
    public void release(RequestContext ctx) {
        pool.release(ctx);
    }

    public RequestContextPool.PoolStats stats() {
        return pool.stats();
    }
}
