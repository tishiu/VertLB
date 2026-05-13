package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;
import io.vertilb.pool.Upstream;

/**
 * Mutable state bag that follows one proxied client request through selection, retry, and logging.
 */
public class RequestContext {
    public String id;
    public String poolName;
    public long startTime;
    public int attempt;
    public HttpServerRequest clientRequest;
    public Upstream selectedUpstream;
    public long durationMs;

    /**
     * Creates an empty request context for framework and test construction.
     */
    public RequestContext() {
    }

    /**
     * Creates a request context for a listener pool and client request.
     *
     * @param poolName selected pool name
     * @param clientRequest incoming Vert.x request
     */
    public RequestContext(String poolName, HttpServerRequest clientRequest) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
