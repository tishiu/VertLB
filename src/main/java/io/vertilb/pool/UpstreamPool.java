package io.vertilb.pool;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.strategy.BalancingStrategy;
import io.vertilb.pool.strategy.LeastConnectionsStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime pool that owns upstreams and delegates selectable-upstream selection
 * to a balancing strategy.
 */
public class UpstreamPool {
    private final String name;
    private final List<Upstream> upstreams;
    private final BalancingStrategy strategy;
    private final boolean unknownSelectable;
    private volatile List<Upstream> selectableUpstreams;

    public UpstreamPool(String name, List<Upstream> upstreams, BalancingStrategy strategy) {
        this(name, upstreams, strategy, true);
    }

    public UpstreamPool(String name,
                        List<Upstream> upstreams,
                        BalancingStrategy strategy,
                        boolean unknownSelectable) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.upstreams = List.copyOf(Objects.requireNonNull(upstreams, "upstreams must not be null"));
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.unknownSelectable = unknownSelectable;

        initializeStrategyMetadata();
        rebuildSelectableCache();
    }

    private void initializeStrategyMetadata() {
        if (strategy instanceof LeastConnectionsStrategy) {
            for (Upstream upstream : upstreams) {
                upstream.metadata().putIfAbsent(
                    LeastConnectionsStrategy.ACTIVE_CONNECTIONS_KEY,
                    new AtomicInteger(0)
                );
            }
        }
    }

    /**
     * Selects one selectable upstream for a request.
     *
     * @param ctx request context
     * @return selected upstream when one is available
     */
    public Optional<Upstream> selectUpstream(RequestContext ctx) {
        List<Upstream> selectable = selectableUpstreams;

        if (selectable.isEmpty()) {
            return Optional.empty();
        }

        Upstream selected = strategy.select(selectable, ctx);
        return Optional.ofNullable(selected);
    }

    /**
     * Returns the cached immutable snapshot of selectable upstreams.
     *
     * HEALTHY is selectable.
     * UNKNOWN is selectable when this pool is configured for optimistic startup.
     * UNHEALTHY is always excluded.
     */
    public List<Upstream> getSelectableUpstreams() {
        return selectableUpstreams;
    }

    /**
     * Legacy name. Returns selectable upstreams, not only HEALTHY upstreams.
     */
    @Deprecated
    public List<Upstream> getHealthyUpstreams() {
        return getSelectableUpstreams();
    }

    /**
     * Updates the health status of one upstream.
     *
     * @param upstreamId upstream identifier to update
     * @param status new health status
     */
    public void updateHealthStatus(String upstreamId, HealthStatus status) {
        for (Upstream upstream : upstreams) {
            if (upstream.id().equals(upstreamId)) {
                if (upstream.healthStatus() == status) {
                    return;
                }

                upstream.setHealthStatus(status);
                rebuildSelectableCache();
                return;
            }
        }
    }

    private void rebuildSelectableCache() {
        List<Upstream> result = new ArrayList<>();

        for (Upstream upstream : upstreams) {
            if (isSelectable(upstream)) {
                result.add(upstream);
            }
        }

        selectableUpstreams = List.copyOf(result);
    }

    private boolean isSelectable(Upstream upstream) {
        if (upstream.healthStatus() == HealthStatus.HEALTHY) {
            return true;
        }

        return upstream.healthStatus() == HealthStatus.UNKNOWN && unknownSelectable;
    }

    /**
     * Completion hook after one selected attempt finishes.
     * CoreEngine calls this instead of touching strategy directly.
     */
    public void onRequestCompleted(Upstream upstream, RequestContext ctx) {
        strategy.onRequestCompleted(upstream, ctx);
    }

    public String name() {
        return name;
    }

    public List<Upstream> upstreams() {
        return upstreams;
    }

    public BalancingStrategy strategy() {
        return strategy;
    }
}
