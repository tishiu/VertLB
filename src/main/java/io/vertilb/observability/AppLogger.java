package io.vertilb.observability;

import io.vertilb.engine.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging facade for request access logs, errors, and health transition events.
 */
public class AppLogger {
    private static final Logger log = LoggerFactory.getLogger(AppLogger.class);

    /**
     * Writes an access log entry for a completed request.
     *
     * @param ctx completed request context
     */
    public void logAccess(RequestContext ctx) {
        log.info(
            "pool={} upstream={} method={} uri={} status={} attempts={} durationMs={}",
            ctx.poolName,
            ctx.selectedUpstreamId,
            ctx.clientRequest != null ? ctx.clientRequest.method() : "-",
            ctx.clientRequest != null ? ctx.clientRequest.uri() : "-",
            ctx.responseStatusCode,
            ctx.attemptCount,
            ctx.durationMs
        );
    }

    /**
     * Writes an error log entry for a failed request or component action.
     *
     * @param message error message
     * @param error associated error
     */
    public void logError(String message, Throwable error) {
        if (error == null) {
            log.warn(message);
            return;
        }

        log.error(message, error);
    }
}
