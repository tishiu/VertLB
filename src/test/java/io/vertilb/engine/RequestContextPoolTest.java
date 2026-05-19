package io.vertilb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestContextPoolTest {
    private HttpServerRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(request.uri()).thenReturn("/api/users/1");
        when(request.response()).thenReturn(response);
    }

    @Test
    void borrowCreatesContextWhenPoolEmpty() {
        RequestContextPool pool = new RequestContextPool(2);

        RequestContext ctx = pool.borrow("user-service", request, "/users/1");

        assertEquals("user-service", ctx.poolName);
        assertEquals("/users/1", ctx.outboundUri());
        assertEquals(1L, pool.stats().created());
        assertEquals(1L, pool.stats().borrowed());
        assertEquals(0L, pool.stats().reused());
    }

    @Test
    void releaseThenBorrowReusesSameInstance() {
        RequestContextPool pool = new RequestContextPool(2);

        RequestContext first = pool.borrow("user-service", request, "/users/1");
        pool.release(first);

        RequestContext second = pool.borrow("order-service", request, null);

        assertSame(first, second);
        assertEquals(1L, pool.stats().reused());
        assertEquals(0, pool.stats().available());
        assertEquals("order-service", second.poolName);
        assertEquals("/api/users/1", second.outboundUri());
    }

    @Test
    void resetClearsPreviousStateOnRelease() {
        RequestContextPool pool = new RequestContextPool(2);

        RequestContext ctx = pool.borrow("user-service", request, "/users/1");
        ctx.selectedUpstreamId = "user-1";
        ctx.responseStatusCode = 503;
        ctx.lastError = new IllegalStateException("boom");
        pool.release(ctx);

        assertNull(ctx.poolName);
        assertNull(ctx.clientRequest);
        assertNull(ctx.rewrittenUri);
        assertNull(ctx.selectedUpstreamId);
        assertEquals(0, ctx.responseStatusCode);
        assertNull(ctx.lastError);
    }

    @Test
    void maxSizeIsRespected() {
        RequestContextPool pool = new RequestContextPool(1);

        RequestContext first = pool.borrow("user-service", request, null);
        RequestContext second = pool.borrow("user-service", request, null);

        pool.release(first);
        pool.release(second);

        assertEquals(1, pool.stats().available());
        assertEquals(1L, pool.stats().dropped());
    }

    @Test
    void releaseNullIsSafe() {
        RequestContextPool pool = new RequestContextPool(1);

        pool.release(null);

        assertEquals(0L, pool.stats().released());
        assertEquals(0, pool.stats().available());
    }

    @Test
    void statsUpdateCorrectly() {
        RequestContextPool pool = new RequestContextPool(2);

        RequestContext first = pool.borrow("user-service", request, null);
        RequestContext second = pool.borrow("user-service", request, null);
        pool.release(first);
        pool.release(second);
        pool.borrow("user-service", request, null);

        RequestContextPool.PoolStats stats = pool.stats();
        assertEquals(2L, stats.created());
        assertEquals(3L, stats.borrowed());
        assertEquals(1L, stats.reused());
        assertEquals(2L, stats.released());
        assertEquals(0L, stats.dropped());
        assertEquals(1, stats.available());
    }
}
