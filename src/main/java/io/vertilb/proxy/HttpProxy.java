package io.vertilb.proxy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import io.vertx.core.Future;

/**
 * HTTP proxy component responsible for forwarding a request to a selected upstream
 * and streaming the response back to the client.
 *
 * This class is intentionally kept as an async boundary.
 * Full Vert.x HttpClient forwarding will be implemented later.
 */
public class HttpProxy {
    /**
     * Forwards a request context to the selected upstream.
     *
     * @param ctx request context
     * @param upstream selected upstream
     * @return future completed when forwarding finishes
     */
    public Future<Void> forward(RequestContext ctx, Upstream upstream) {
        return Future.failedFuture(new UnsupportedOperationException("HttpProxy implementation TODO"));
    }
}