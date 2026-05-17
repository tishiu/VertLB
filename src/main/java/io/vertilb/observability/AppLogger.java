package io.vertilb.observability;

import io.vertilb.engine.RequestContext;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured logging facade for request access logs, errors, and health transition events.
 */
public class AppLogger {
    private final Logger log;
    private final Level configuredLevel;

    public AppLogger() {
        this("INFO");
    }

    public AppLogger(String configuredLevel) {
        this(LoggerFactory.getLogger(AppLogger.class), configuredLevel);
    }

    AppLogger(Logger log, String configuredLevel) {
        this.log = log;
        this.configuredLevel = Level.from(configuredLevel);
    }

    /**
     * Writes an access log entry for a completed request.
     *
     * @param ctx completed request context
     */
    public void logAccess(RequestContext ctx) {
        info(
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
            warn(message);
            return;
        }

        error(message, error);
    }

    public void error(String message) {
        if (isEnabled(Level.ERROR)) {
            log.error(message);
        }
    }

    public void error(String message, Throwable error) {
        if (isEnabled(Level.ERROR)) {
            if (error == null) {
                log.error(message);
            } else {
                log.error(message, error);
            }
        }
    }

    public void warn(String message) {
        if (isEnabled(Level.WARN)) {
            log.warn(message);
        }
    }

    public void warn(String message, Throwable error) {
        if (isEnabled(Level.WARN)) {
            if (error == null) {
                log.warn(message);
            } else {
                log.warn(message, error);
            }
        }
    }

    public void info(String message) {
        if (isEnabled(Level.INFO)) {
            log.info(message);
        }
    }

    public void info(String message, Object... args) {
        if (isEnabled(Level.INFO)) {
            log.info(message, args);
        }
    }

    public void debug(String message) {
        if (isEnabled(Level.DEBUG)) {
            log.debug(message);
        }
    }

    public void debug(String message, Object... args) {
        if (isEnabled(Level.DEBUG)) {
            log.debug(message, args);
        }
    }

    public void trace(String message) {
        if (isEnabled(Level.TRACE)) {
            log.trace(message);
        }
    }

    public void trace(String message, Object... args) {
        if (isEnabled(Level.TRACE)) {
            log.trace(message, args);
        }
    }

    private boolean isEnabled(Level level) {
        return configuredLevel.allows(level);
    }

    public enum Level {
        ERROR,
        WARN,
        INFO,
        DEBUG,
        TRACE;

        public static Level from(String value) {
            if (value == null || value.isBlank()) {
                return INFO;
            }

            return Level.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        public boolean allows(Level messageLevel) {
            return messageLevel.ordinal() <= ordinal();
        }
    }
}
