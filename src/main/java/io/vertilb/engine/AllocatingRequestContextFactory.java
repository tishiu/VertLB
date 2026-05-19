package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;

public final class AllocatingRequestContextFactory implements RequestContextFactory {
    @Override
    public RequestContext create(String poolName, HttpServerRequest request, String rewrittenUri) {
        RequestContext ctx = new RequestContext(poolName, request);
        ctx.rewrittenUri = rewrittenUri;
        return ctx;
    }

    @Override
    public void release(RequestContext ctx) {
    }
}
