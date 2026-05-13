package io.vertilb.engine.error;

/**
 * Exception raised when a pool cannot provide any healthy upstream for a request.
 */
public class NoHealthyUpstreamsException extends ProxyException {
    public NoHealthyUpstreamsException(String message) {
        super(message);
    }

    public NoHealthyUpstreamsException(String message, Throwable cause) {
        super(message, cause);
    }
}
