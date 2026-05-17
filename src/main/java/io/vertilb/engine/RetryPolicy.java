package io.vertilb.engine;

import java.util.Set;

/**
 * Immutable retry policy derived from configuration.
 */
public final class RetryPolicy {
    private static final Set<String> RETRYABLE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final int maxAttempts;
    private final Set<Integer> retryableStatuses;
    private final long backoffMs;

    public RetryPolicy(int maxAttempts,
                       Set<Integer> retryableStatuses,
                       long backoffMs) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }

        if (backoffMs < 0) {
            throw new IllegalArgumentException("backoffMs must be at least 0");
        }

        this.maxAttempts = maxAttempts;
        this.retryableStatuses = Set.copyOf(retryableStatuses);
        this.backoffMs = backoffMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int maxRetries() {
        return maxAttempts - 1;
    }

    public long backoffMs() {
        return backoffMs;
    }

    public Set<Integer> retryableStatuses() {
        return retryableStatuses;
    }

    public boolean isRetryableStatus(int statusCode) {
        return retryableStatuses.contains(statusCode);
    }

    public boolean isRetryableMethod(RequestContext ctx) {
        if (ctx == null || ctx.clientRequest == null || ctx.clientRequest.method() == null) {
            return false;
        }

        return RETRYABLE_METHODS.contains(ctx.clientRequest.method().name().toUpperCase());
    }

    public boolean shouldRetry(RequestContext ctx) {
        if (ctx.attemptCount >= maxAttempts) {
            return false;
        }

        if (!isRetryableMethod(ctx)) {
            return false;
        }

        if (ctx.lastError != null && ctx.responseStatusCode == 0) {
            return true;
        }

        return isRetryableStatus(ctx.responseStatusCode);
    }
}
