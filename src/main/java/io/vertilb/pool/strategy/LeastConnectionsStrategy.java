package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;

/**
 * Balancing strategy contract for selecting the healthy upstream with the fewest active requests.
 */
public class LeastConnectionsStrategy implements BalancingStrategy {
    @Override
    public Upstream select(List<Upstream> healthyUpstreams, RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void onRequestCompleted(Upstream upstream, RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
