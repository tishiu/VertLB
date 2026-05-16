package io.vertilb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;

class RequestContextTest {
    @Test
    void outboundUriUsesRewrittenUriWhenPresent() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.uri()).thenReturn("/api/users/1?expand=true");

        RequestContext ctx = new RequestContext("user-service", request);
        ctx.rewrittenUri = "/users/1?expand=true";

        assertEquals("/users/1?expand=true", ctx.outboundUri());
    }

    @Test
    void outboundUriFallsBackToClientRequestUri() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.uri()).thenReturn("/api/users/1?expand=true");

        RequestContext ctx = new RequestContext("user-service", request);

        assertEquals("/api/users/1?expand=true", ctx.outboundUri());
    }
}
