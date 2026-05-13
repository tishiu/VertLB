package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;

/**
 * Selects an upstream from the healthy members of a pool and receives completion callbacks.
 */
public interface BalancingStrategy {
    /**
     * Selects one upstream from the supplied healthy upstreams.
     *
     * @param healthyUpstreams upstreams eligible for selection
     * @param ctx request context
     * @return selected upstream
     */
    Upstream select(List<Upstream> healthyUpstreams, RequestContext ctx);

    /**
     * Called after a request completes, whether successful or retryable failure.
     *
     * @param upstream selected upstream
     * @param ctx request context
     */
    default void onRequestCompleted(Upstream upstream, RequestContext ctx) {
        // TODO
    }
}
