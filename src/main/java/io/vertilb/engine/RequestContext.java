package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Mutable request-scoped state that follows one proxied client request
 * through upstream selection, retry, forwarding, and observability.
 */
public class RequestContext {
    public String poolName;
    public HttpServerRequest clientRequest;
    public long startTime;

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
        reset();
    }

    /**
     * Creates a request context for a listener pool and client request.
     *
     * @param poolName selected pool name
     * @param clientRequest incoming Vert.x request
     */
    public RequestContext(String poolName, HttpServerRequest clientRequest) {
        init(poolName, clientRequest, null);
    }

    public RequestContext init(String poolName, HttpServerRequest clientRequest, String rewrittenUri) {
        this.poolName = poolName;
        this.clientRequest = clientRequest;
        this.rewrittenUri = rewrittenUri;
        this.selectedUpstreamId = null;
        this.attemptCount = 0;
        this.responseStatusCode = 0;
        this.durationMs = 0L;
        this.lastError = null;
        this.startTime = System.currentTimeMillis();
        return this;
    }

    public void reset() {
        this.poolName = null;
        this.clientRequest = null;
        this.rewrittenUri = null;
        this.selectedUpstreamId = null;
        this.attemptCount = 0;
        this.responseStatusCode = 0;
        this.durationMs = 0L;
        this.lastError = null;
        this.startTime = 0L;
    }

    public HttpServerResponse response() {
        return clientRequest.response();
    }

    public String outboundUri() {
        if (clientRequest == null) {
            throw new IllegalStateException("RequestContext is not initialized");
        }

        return rewrittenUri != null ? rewrittenUri : clientRequest.uri();
    }
}
