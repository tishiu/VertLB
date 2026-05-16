package io.vertilb.gateway;

import io.vertx.core.http.HttpServerRequest;
import io.vertilb.config.RouteConfig;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves an inbound HTTP request to a target upstream pool.
 */
public class GatewayRouter {
    private final List<RouteConfig> routes;

    public GatewayRouter(List<RouteConfig> routes) {
        this.routes = List.copyOf(Objects.requireNonNull(routes, "routes must not be null"));
    }

    public RouteDecision resolve(HttpServerRequest request) {
        String path = request.path();
        String method = request.method().name().toUpperCase(Locale.ROOT);
        String host = normalizeHost(request.host());

        for (RouteConfig route : routes) {
            if (!matchesHost(route, host)) {
                continue;
            }

            if (!matchesMethod(route, method)) {
                continue;
            }

            if (!matchesPath(route, path)) {
                continue;
            }

            String rewrittenUri = rewriteUri(route, path, request.query());
            return new RouteDecision(route.poolName, rewrittenUri);
        }

        throw new IllegalArgumentException("No route matched: " + method + " " + path);
    }

    private boolean matchesHost(RouteConfig route, String requestHost) {
        if (route.host == null || route.host.isBlank()) {
            return true;
        }

        return normalizeHost(route.host).equalsIgnoreCase(requestHost);
    }

    private boolean matchesMethod(RouteConfig route, String method) {
        if (route.methods == null || route.methods.isEmpty()) {
            return true;
        }

        for (String allowed : route.methods) {
            if (allowed != null && allowed.equalsIgnoreCase(method)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesPath(RouteConfig route, String path) {
        return route.pathPrefix != null
            && !route.pathPrefix.isBlank()
            && path.startsWith(route.pathPrefix);
    }

    private String rewriteUri(RouteConfig route, String path, String query) {
        String result = path;

        if (route.stripPrefix != null
            && !route.stripPrefix.isBlank()
            && result.startsWith(route.stripPrefix)) {

            result = result.substring(route.stripPrefix.length());

            if (result.isBlank()) {
                result = "/";
            }

            if (!result.startsWith("/")) {
                result = "/" + result;
            }
        }

        if (route.addPrefix != null && !route.addPrefix.isBlank()) {
            String prefix = route.addPrefix.endsWith("/")
                ? route.addPrefix.substring(0, route.addPrefix.length() - 1)
                : route.addPrefix;

            result = prefix + result;
        }

        if (query != null && !query.isBlank()) {
            result = result + "?" + query;
        }

        return result;
    }

    private String normalizeHost(String host) {
        if (host == null) {
            return "";
        }

        int colon = host.indexOf(':');
        if (colon >= 0) {
            return host.substring(0, colon);
        }

        return host;
    }
}
