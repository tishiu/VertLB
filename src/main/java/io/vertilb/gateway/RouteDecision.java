package io.vertilb.gateway;

/**
 * Result of matching one inbound request to one upstream pool.
 */
public class RouteDecision {
    private final String poolName;
    private final String rewrittenUri;

    public RouteDecision(String poolName, String rewrittenUri) {
        this.poolName = poolName;
        this.rewrittenUri = rewrittenUri;
    }

    public String poolName() {
        return poolName;
    }

    public String rewrittenUri() {
        return rewrittenUri;
    }
}
