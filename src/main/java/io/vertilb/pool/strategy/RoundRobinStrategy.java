package io.vertilb.pool.strategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;

/**
 * Balancing strategy for selecting selectable upstreams in round-robin order.
 */
public class RoundRobinStrategy implements BalancingStrategy {
    private final AtomicLong counter = new AtomicLong();

    @Override
    public Upstream select(List<Upstream> healthyUpstreams, RequestContext ctx) {
        if (healthyUpstreams == null || healthyUpstreams.isEmpty()) {
            return null;
        }

        int index = Math.floorMod(counter.getAndIncrement(), healthyUpstreams.size());
        return healthyUpstreams.get(index);
    }
}