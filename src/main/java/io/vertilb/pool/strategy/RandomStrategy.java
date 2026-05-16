package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selects a random upstream from selectable upstreams.
 */
public class RandomStrategy implements BalancingStrategy {
    @Override
    public Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx) {
        if (selectableUpstreams == null || selectableUpstreams.isEmpty()) {
            return null;
        }

        int index = ThreadLocalRandom.current().nextInt(selectableUpstreams.size());
        return selectableUpstreams.get(index);
    }
}
