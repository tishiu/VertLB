package io.vertilb.pool;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.strategy.BalancingStrategy;
import java.util.List;
import java.util.Optional;

/**
 * Runtime pool that owns upstreams and delegates healthy-upstream selection to a balancing strategy.
 */
public class UpstreamPool {
    private String name;
    private List<Upstream> upstreams;
    private BalancingStrategy strategy;

    /**
     * Creates a runtime upstream pool.
     *
     * @param name pool name referenced by listeners
     * @param upstreams runtime upstreams in the pool
     * @param strategy balancing strategy used by this pool
     */
    public UpstreamPool(String name, List<Upstream> upstreams, BalancingStrategy strategy) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Selects one healthy upstream for a request.
     *
     * @param ctx request context
     * @return selected upstream when one is available
     */
    public Optional<Upstream> selectUpstream(RequestContext ctx) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Returns only upstreams that are currently selectable.
     *
     * @return healthy upstream list
     */
    public List<Upstream> getHealthyUpstreams() {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Updates the health status of one upstream after a probe threshold transition.
     *
     * @param upstreamId upstream identifier to update
     * @param status new health status
     */
    public void updateHealthStatus(String upstreamId, HealthStatus status) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
