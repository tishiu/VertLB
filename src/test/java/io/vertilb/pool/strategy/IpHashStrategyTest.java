package io.vertilb.pool.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;
import org.junit.jupiter.api.Test;

class IpHashStrategyTest {
    @Test
    void selectsByClientIpHash() {
        List<Upstream> upstreams = List.of(upstream("one"), upstream("two"), upstream("three"));
        String ip = "10.0.0.42";

        Upstream selected = new IpHashStrategy().select(upstreams, contextWithRemoteIp(ip));

        assertEquals(upstreams.get(Math.floorMod(ip.hashCode(), upstreams.size())), selected);
    }

    @Test
    void fallsBackToFirstUpstreamWhenClientIpUnavailable() {
        List<Upstream> upstreams = List.of(upstream("one"), upstream("two"));

        assertEquals(upstreams.get(0), new IpHashStrategy().select(upstreams, null));
    }

    @Test
    void returnsNullForEmptySelectableList() {
        assertNull(new IpHashStrategy().select(List.of(), null));
    }

    private RequestContext contextWithRemoteIp(String ip) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, ip));
        return new RequestContext("pool", request);
    }

    private Upstream upstream(String id) {
        return new Upstream(id, "localhost", 8080, "http", 1, null);
    }
}
