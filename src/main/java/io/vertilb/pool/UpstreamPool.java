package io.vertilb.pool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.strategy.BalancingStrategy;

/**
 * Runtime pool that owns upstreams and delegates selectable-upstream selection
 * to a balancing strategy.
 */
public class UpstreamPool {
    private final String name;
    private final List<Upstream> upstreams;
    private final BalancingStrategy strategy;

    public UpstreamPool(String name, List<Upstream> upstreams, BalancingStrategy strategy) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.upstreams = List.copyOf(Objects.requireNonNull(upstreams, "upstreams must not be null"));
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
    }

    /**
     * Selects one selectable upstream for a request.
     *
     * @param ctx request context
     * @return selected upstream when one is available
     */
    public Optional<Upstream> selectUpstream(RequestContext ctx) {
        List<Upstream> selectable = getHealthyUpstreams();

        if (selectable.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(strategy.select(selectable, ctx));
    }

    /**
     * The method name is kept for scaffold compatibility.
     * Actual behavior: returns selectable upstreams.
     *
     * UNKNOWN + HEALTHY pass.
     * UNHEALTHY is excluded.
     */
    public List<Upstream> getHealthyUpstreams() {
        List<Upstream> result = new ArrayList<>();

        for (Upstream upstream : upstreams) {
            if (upstream.isSelectable()) {
                result.add(upstream);
            }
        }

        return result;
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
                upstream.setHealthStatus(status);
                return;
            }
        }
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