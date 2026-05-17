package io.vertilb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void maxAttemptsThreeAllowsRetryAfterAttemptOneAndTwoButNotThree() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(503), 100L);

        RequestContext firstAttempt = contextWith("GET", 1, 503, null);
        RequestContext secondAttempt = contextWith("GET", 2, 503, null);
        RequestContext thirdAttempt = contextWith("GET", 3, 503, null);

        assertTrue(policy.shouldRetry(firstAttempt));
        assertTrue(policy.shouldRetry(secondAttempt));
        assertFalse(policy.shouldRetry(thirdAttempt));
    }

    @Test
    void postDoesNotRetry() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(503), 100L);

        RequestContext ctx = contextWith("POST", 1, 503, null);

        assertFalse(policy.shouldRetry(ctx));
    }

    @Test
    void transportErrorRetriesForSafeMethodWhenStatusCodeIsZero() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(503), 100L);

        RequestContext ctx = contextWith("GET", 1, 0, new RuntimeException("connection reset"));

        assertTrue(policy.shouldRetry(ctx));
    }

    @Test
    void configuredRetryableStatusRetries() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(429), 100L);

        RequestContext ctx = contextWith("GET", 1, 429, null);

        assertTrue(policy.shouldRetry(ctx));
    }

    @Test
    void nonConfiguredStatusDoesNotRetry() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(503), 100L);

        RequestContext ctx = contextWith("GET", 1, 500, null);

        assertFalse(policy.shouldRetry(ctx));
    }

    @Test
    void maxRetriesReturnsMaxAttemptsMinusOne() {
        RetryPolicy policy = new RetryPolicy(3, Set.of(503), 100L);

        assertEquals(2, policy.maxRetries());
    }

    private RequestContext contextWith(String method,
                                       int attemptCount,
                                       int responseStatusCode,
                                       Throwable lastError) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.method()).thenReturn(HttpMethod.valueOf(method));

        RequestContext ctx = new RequestContext("user-service", request);
        ctx.attemptCount = attemptCount;
        ctx.responseStatusCode = responseStatusCode;
        ctx.lastError = lastError;
        return ctx;
    }
}
