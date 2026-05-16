package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Selects upstreams in round-robin order.
 */
public class RoundRobinStrategy implements BalancingStrategy {
    private final AtomicLong counter = new AtomicLong();

    @Override
    public Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx) {
        if (selectableUpstreams == null || selectableUpstreams.isEmpty()) {
            return null;
        }

        int index = Math.floorMod(counter.getAndIncrement(), selectableUpstreams.size());
        return selectableUpstreams.get(index);
    }
}
