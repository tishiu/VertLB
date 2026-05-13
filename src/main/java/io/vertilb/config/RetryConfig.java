package io.vertilb.config;

import java.util.List;

/**
 * Configuration for retry attempts, retryable response statuses, and retry backoff.
 */
public class RetryConfig {
    public Integer maxAttempts;
    public List<Integer> retryableStatuses;
    public Long backoffMs;
}
