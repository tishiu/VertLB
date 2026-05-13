package io.vertilb.engine.error;

/**
 * Base runtime exception for proxy and engine failures that occur while handling a request.
 */
public class ProxyException extends RuntimeException {
    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
