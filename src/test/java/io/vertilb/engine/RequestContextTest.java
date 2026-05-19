package io.vertilb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    private HttpServerRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(request.uri()).thenReturn("/api/users/1?expand=true");
        when(request.response()).thenReturn(response);
    }

    @Test
    void outboundUriUsesRewrittenUriWhenPresent() {
        RequestContext ctx = new RequestContext("user-service", request);
        ctx.rewrittenUri = "/users/1?expand=true";

        assertEquals("/users/1?expand=true", ctx.outboundUri());
    }

    @Test
    void outboundUriFallsBackToClientRequestUri() {
        RequestContext ctx = new RequestContext("user-service", request);

        assertEquals("/api/users/1?expand=true", ctx.outboundUri());
    }

    @Test
    void initSetsExpectedFields() {
        RequestContext ctx = new RequestContext();
        Throwable error = new IllegalStateException("boom");

        ctx.init("user-service", request, "/users/1");
        ctx.attemptCount = 2;
        ctx.selectedUpstreamId = "user-1";
        ctx.responseStatusCode = 503;
        ctx.durationMs = 25L;
        ctx.lastError = error;

        assertEquals("user-service", ctx.poolName);
        assertEquals(request, ctx.clientRequest);
        assertEquals("/users/1", ctx.rewrittenUri);
        assertEquals("/users/1", ctx.outboundUri());
        assertEquals(2, ctx.attemptCount);
        assertEquals("user-1", ctx.selectedUpstreamId);
        assertEquals(503, ctx.responseStatusCode);
        assertEquals(25L, ctx.durationMs);
        assertEquals(error, ctx.lastError);
    }

    @Test
    void resetClearsAllRequestScopedState() {
        RequestContext ctx = new RequestContext("user-service", request);
        ctx.rewrittenUri = "/users/1";
        ctx.attemptCount = 2;
        ctx.selectedUpstreamId = "user-1";
        ctx.responseStatusCode = 503;
        ctx.durationMs = 25L;
        ctx.lastError = new IllegalStateException("boom");

        ctx.reset();

        assertNull(ctx.poolName);
        assertNull(ctx.clientRequest);
        assertNull(ctx.rewrittenUri);
        assertNull(ctx.selectedUpstreamId);
        assertEquals(0, ctx.attemptCount);
        assertEquals(0, ctx.responseStatusCode);
        assertEquals(0L, ctx.durationMs);
        assertNull(ctx.lastError);
    }

    @Test
    void reusedContextDoesNotLeakPreviousState() {
        RequestContext ctx = new RequestContext("user-service", request);
        ctx.rewrittenUri = "/users/1";
        ctx.responseStatusCode = 503;
        ctx.lastError = new IllegalStateException("boom");

        ctx.reset();
        ctx.init("order-service", request, null);

        assertEquals("order-service", ctx.poolName);
        assertEquals("/api/users/1?expand=true", ctx.outboundUri());
        assertEquals(0, ctx.responseStatusCode);
        assertNull(ctx.lastError);
    }

    @Test
    void outboundUriFailsClearlyAfterReset() {
        RequestContext ctx = new RequestContext("user-service", request);

        ctx.reset();

        assertThrows(IllegalStateException.class, ctx::outboundUri);
    }
}
