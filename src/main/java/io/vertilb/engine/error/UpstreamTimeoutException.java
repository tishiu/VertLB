package io.vertilb.engine.error;

/**
 * Exception raised when an upstream request exceeds the configured timeout.
 */
public class UpstreamTimeoutException extends ProxyException {
    public UpstreamTimeoutException(String message) {
        super(message);
    }

    public UpstreamTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
