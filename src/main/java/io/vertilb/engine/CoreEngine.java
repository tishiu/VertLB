package io.vertilb.engine;

/**
 * Core request engine that selects upstreams, drives retry attempts, records metrics, and writes logs.
 */
public class CoreEngine {
    /**
     * Handles one proxied request from listener through upstream forwarding.
     *
     * @param ctx request context
     */
    public void handleRequest(RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
