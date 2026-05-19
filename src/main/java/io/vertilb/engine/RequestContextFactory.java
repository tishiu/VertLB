package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;

public interface RequestContextFactory {
    RequestContext create(String poolName, HttpServerRequest request, String rewrittenUri);

    void release(RequestContext ctx);
}
