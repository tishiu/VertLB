package io.vertilb.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertilb.config.RouteConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouterTest {
    @Test
    void resolvesRouteAndRewritesPathWithQuery() {
        RouteConfig route = new RouteConfig();
        route.host = "example.test";
        route.pathPrefix = "/api/users";
        route.methods = List.of("GET");
        route.poolName = "user-service";
        route.stripPrefix = "/api";

        HttpServerRequest request = request("example.test:8080", HttpMethod.GET, "/api/users/1", "expand=true");

        RouteDecision decision = new GatewayRouter(List.of(route)).resolve(request);

        assertEquals("user-service", decision.poolName());
        assertEquals("/users/1?expand=true", decision.rewrittenUri());
    }

    @Test
    void rejectsWhenMethodDoesNotMatch() {
        RouteConfig route = new RouteConfig();
        route.pathPrefix = "/api/users";
        route.methods = List.of("POST");
        route.poolName = "user-service";

        HttpServerRequest request = request("example.test", HttpMethod.GET, "/api/users", null);

        assertThrows(IllegalArgumentException.class, () -> new GatewayRouter(List.of(route)).resolve(request));
    }

    private HttpServerRequest request(String host, HttpMethod method, String path, String query) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.host()).thenReturn(host);
        when(request.method()).thenReturn(method);
        when(request.path()).thenReturn(path);
        when(request.query()).thenReturn(query);
        return request;
    }
}
