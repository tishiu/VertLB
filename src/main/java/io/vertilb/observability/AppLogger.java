package io.vertilb.observability;

import io.vertilb.engine.RequestContext;

/**
 * Structured logging facade for request access logs, errors, and health transition events.
 */
public class AppLogger {
    /**
     * Writes an access log entry for a completed request.
     *
     * @param ctx completed request context
     */
    public void logAccess(RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Writes an error log entry for a failed request or component action.
     *
     * @param message error message
     * @param error associated error
     */
    public void logError(String message, Throwable error) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
