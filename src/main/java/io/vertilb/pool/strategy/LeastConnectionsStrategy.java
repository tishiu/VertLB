package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selects the upstream with the fewest active requests.
 */
public class LeastConnectionsStrategy implements BalancingStrategy {
    public static final String ACTIVE_CONNECTIONS_KEY = "activeConnections";

    @Override
    public Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx) {
        if (selectableUpstreams == null || selectableUpstreams.isEmpty()) {
            return null;
        }

        Upstream best = selectableUpstreams.get(0);
        int bestCount = activeCount(best);

        for (int i = 1; i < selectableUpstreams.size(); i++) {
            Upstream candidate = selectableUpstreams.get(i);
            int candidateCount = activeCount(candidate);

            if (candidateCount < bestCount) {
                best = candidate;
                bestCount = candidateCount;
            }
        }

        increment(best);
        return best;
    }

    @Override
    public void onRequestCompleted(Upstream upstream, RequestContext ctx) {
        if (upstream == null) {
            return;
        }

        decrement(upstream);
    }

    private int activeCount(Upstream upstream) {
        Object value = upstream.metadata().get(ACTIVE_CONNECTIONS_KEY);

        if (value instanceof AtomicInteger counter) {
            return counter.get();
        }

        return 0;
    }

    private void increment(Upstream upstream) {
        Object value = upstream.metadata().get(ACTIVE_CONNECTIONS_KEY);

        if (value instanceof AtomicInteger counter) {
            counter.incrementAndGet();
        }
    }

    private void decrement(Upstream upstream) {
        Object value = upstream.metadata().get(ACTIVE_CONNECTIONS_KEY);

        if (value instanceof AtomicInteger counter) {
            counter.updateAndGet(current -> Math.max(0, current - 1));
        }
    }
}
