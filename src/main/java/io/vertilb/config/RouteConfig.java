package io.vertilb.config;

import java.util.List;

/**
 * Gateway route rule that maps an inbound request to an upstream pool.
 */
public class RouteConfig {
    public String id;

    /**
     * Optional host match.
     * If null or blank, the route matches any host.
     */
    public String host;

    /**
     * Required path prefix match.
     * Example: /api/users
     */
    public String pathPrefix;

    /**
     * Optional HTTP method match.
     * If null or empty, the route matches any method.
     * Example: ["GET", "POST"]
     */
    public List<String> methods;

    /**
     * Target pool name.
     */
    public String poolName;

    /**
     * Optional prefix to strip before forwarding.
     * Example:
     * /api/users/1 with stripPrefix=/api becomes /users/1.
     */
    public String stripPrefix;

    /**
     * Optional prefix to add after stripping.
     */
    public String addPrefix;
}
