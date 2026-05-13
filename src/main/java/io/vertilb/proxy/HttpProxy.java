package io.vertilb.proxy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;

/**
 * HTTP proxy component responsible for forwarding a request to a selected upstream and streaming
 * the response back to the client.
 */
public class HttpProxy {
    /**
     * Forwards a request context to the selected upstream.
     *
     * @param ctx request context
     * @param upstream selected upstream
     */
    public void forward(RequestContext ctx, Upstream upstream) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
