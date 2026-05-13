package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;

/**
 * Balancing strategy contract for selecting healthy upstreams by client IP hash.
 */
public class IpHashStrategy implements BalancingStrategy {
    @Override
    public Upstream select(List<Upstream> healthyUpstreams, RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
