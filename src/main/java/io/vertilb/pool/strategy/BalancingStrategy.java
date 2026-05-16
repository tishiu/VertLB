package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;

/**
 * Selects an upstream from the selectable members of a pool and receives completion callbacks.
 */
public interface BalancingStrategy {
    /**
     * Selects one upstream from the supplied selectable upstreams.
     *
     * @param selectableUpstreams upstreams already filtered by UpstreamPool
     * @param ctx request context
     * @return selected upstream, or null if none can be selected
     */
    Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx);

    /**
     * Called after a selected request attempt completes.
     *
     * @param upstream selected upstream
     * @param ctx request context
     */
    default void onRequestCompleted(Upstream upstream, RequestContext ctx) {
    }
}
