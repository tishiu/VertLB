package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Mutable request-scoped state that follows one proxied client request
 * through upstream selection, retry, forwarding, and observability.
 */
public class RequestContext {
    public final String poolName;
    public final HttpServerRequest clientRequest;
    public final long startTime;

    public String rewrittenUri;
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
        this.startTime = System.currentTimeMillis();
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

    public String outboundUri() {
        return rewrittenUri != null ? rewrittenUri : clientRequest.uri();
    }
}
