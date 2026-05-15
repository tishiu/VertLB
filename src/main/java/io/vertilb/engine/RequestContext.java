package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Mutable state bag that follows one proxied client request through selection, retry, and logging.
 */
public class RequestContext {
    public final String poolName;
    public final HttpServerRequest clientRequest;
    public final long startTime;

    public String selectedUpstreamId;
    public int attemptCount;
    public int responseStatusCode;
    public long durationMs;
    public Throwable lastError;

    /**
     * Creates an empty request context for framework and test construction.
     */
    public RequestContext() {
        this.poolName = null;
        this.clientRequest = null;
        this.startTime = 0;
    }

    /**
     * Creates a request context for a listener pool and client request.
     *
     * @param poolName selected pool name
     * @param clientRequest incoming Vert.x request
     */
    public RequestContext(String poolName, HttpServerRequest clientRequest) {
        this.poolName = poolName;
        this.clientRequest = clientRequest;
        this.startTime = System.currentTimeMillis();
    }

    public HttpServerResponse response() {
        return clientRequest.response();
    }
}
