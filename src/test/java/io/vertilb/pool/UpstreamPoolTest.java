package io.vertilb.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.strategy.BalancingStrategy;
import io.vertilb.pool.strategy.LeastConnectionsStrategy;
import io.vertilb.pool.strategy.RoundRobinStrategy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests upstream pool selectable filtering, health updates, and strategy completion hooks.
 */
class UpstreamPoolTest {
    @Test
    void selectsSelectableUpstreamWhenAvailable() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), new RoundRobinStrategy());

        assertEquals(first, pool.selectUpstream(null).orElseThrow());
    }

    @Test
    void unknownUpstreamIsSelectable() {
        Upstream upstream = upstream("first");
        UpstreamPool pool = new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy());

        assertEquals(upstream, pool.selectUpstream(null).orElseThrow());
        assertEquals(List.of(upstream), pool.getSelectableUpstreams());
    }

    @Test
    void healthyUpstreamIsSelectable() {
        Upstream upstream = upstream("first");
        upstream.setHealthStatus(HealthStatus.HEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy());

        assertEquals(upstream, pool.selectUpstream(null).orElseThrow());
        assertEquals(List.of(upstream), pool.getSelectableUpstreams());
    }

    @Test
    void unhealthyUpstreamIsExcluded() {
        Upstream upstream = upstream("first");
        upstream.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy());

        assertTrue(pool.selectUpstream(null).isEmpty());
        assertEquals(List.of(), pool.getSelectableUpstreams());
    }

    @Test
    void legacyHealthyAliasMatchesSelectableUpstreams() {
        Upstream unknown = upstream("unknown");
        Upstream healthy = upstream("healthy");
        healthy.setHealthStatus(HealthStatus.HEALTHY);
        Upstream unhealthy = upstream("unhealthy");
        unhealthy.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool(
            "pool",
            List.of(unknown, healthy, unhealthy),
            new RoundRobinStrategy()
        );

        assertEquals(pool.getSelectableUpstreams(), pool.getHealthyUpstreams());
    }

    @Test
    void returnsEmptyWhenNoSelectableUpstreamsExist() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        first.setHealthStatus(HealthStatus.UNHEALTHY);
        second.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), new RoundRobinStrategy());

        assertTrue(pool.selectUpstream(null).isEmpty());
    }

    @Test
    void updatesUpstreamHealthStatusById() {
        Upstream upstream = upstream("first");
        UpstreamPool pool = new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy());

        pool.updateHealthStatus("first", HealthStatus.HEALTHY);

        assertEquals(HealthStatus.HEALTHY, upstream.healthStatus());
    }

    @Test
    void updateHealthStatusRebuildsSelectableCache() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), new RoundRobinStrategy());

        assertEquals(first, pool.selectUpstream(null).orElseThrow());

        pool.updateHealthStatus("first", HealthStatus.UNHEALTHY);

        assertEquals(second, pool.selectUpstream(null).orElseThrow());
        assertEquals(List.of(second), pool.getSelectableUpstreams());

        pool.updateHealthStatus("first", HealthStatus.HEALTHY);

        assertTrue(pool.getSelectableUpstreams().contains(first));
        assertEquals(first, pool.selectUpstream(null).orElseThrow());
    }

    @Test
    void reusesSelectableSnapshotWhileHealthDoesNotChange() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        CapturingStrategy strategy = new CapturingStrategy();
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), strategy);

        pool.selectUpstream(null);
        List<Upstream> firstSnapshot = strategy.lastSelectableUpstreams;

        pool.selectUpstream(null);

        assertSame(firstSnapshot, strategy.lastSelectableUpstreams);
    }

    @Test
    void initializesLeastConnectionsCountersForEachUpstream() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");

        new UpstreamPool("pool", List.of(first, second), new LeastConnectionsStrategy());

        assertCounter(first, 0);
        assertCounter(second, 0);
    }

    @Test
    void leastConnectionsSelectionIncrementsAndCompletionDecrementsCounter() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), new LeastConnectionsStrategy());

        Upstream selected = pool.selectUpstream(null).orElseThrow();

        assertCounter(selected, 1);

        pool.onRequestCompleted(selected, null);

        assertCounter(selected, 0);
    }

    @Test
    void filtersUnhealthyUpstreamsBeforeDelegatingToStrategy() {
        Upstream first = upstream("first");
        Upstream second = upstream("second");
        first.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(first, second), new RoundRobinStrategy());

        assertEquals(second, pool.selectUpstream(null).orElseThrow());
        assertEquals(List.of(second), pool.getSelectableUpstreams());
    }

    @Test
    void selectUpstreamReturnsUnknownWhenItIsOnlyNonUnhealthyUpstream() {
        Upstream unknown = upstream("unknown");
        Upstream unhealthy = upstream("unhealthy");
        unhealthy.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(unknown, unhealthy), new RoundRobinStrategy());

        assertEquals(List.of(unknown), pool.getSelectableUpstreams());
        assertEquals(unknown, pool.selectUpstream(null).orElseThrow());
    }

    @Test
    void strictModeExcludesUnknownUpstream() {
        Upstream unknown = upstream("unknown");
        UpstreamPool pool = new UpstreamPool("pool", List.of(unknown), new RoundRobinStrategy(), false);

        assertTrue(pool.getSelectableUpstreams().isEmpty());
        assertTrue(pool.selectUpstream(null).isEmpty());
    }

    @Test
    void strictModeAllowsHealthyUpstream() {
        Upstream healthy = upstream("healthy");
        healthy.setHealthStatus(HealthStatus.HEALTHY);
        UpstreamPool pool = new UpstreamPool("pool", List.of(healthy), new RoundRobinStrategy(), false);

        assertEquals(List.of(healthy), pool.getSelectableUpstreams());
        assertEquals(healthy, pool.selectUpstream(null).orElseThrow());
    }

    @Test
    void strictModeReturnsEmptyWhenOnlyUnknownAndUnhealthyUpstreamsExist() {
        Upstream unknown = upstream("unknown");
        Upstream unhealthy = upstream("unhealthy");
        unhealthy.setHealthStatus(HealthStatus.UNHEALTHY);
        UpstreamPool pool = new UpstreamPool(
            "pool",
            List.of(unknown, unhealthy),
            new RoundRobinStrategy(),
            false
        );

        assertTrue(pool.getSelectableUpstreams().isEmpty());
        assertTrue(pool.selectUpstream(null).isEmpty());
    }

    @Test
    void updateHealthStatusRebuildsSelectableCacheInStrictMode() {
        Upstream upstream = upstream("first");
        UpstreamPool pool = new UpstreamPool("pool", List.of(upstream), new RoundRobinStrategy(), false);

        assertTrue(pool.getSelectableUpstreams().isEmpty());

        pool.updateHealthStatus("first", HealthStatus.HEALTHY);

        assertEquals(List.of(upstream), pool.getSelectableUpstreams());
        assertEquals(upstream, pool.selectUpstream(null).orElseThrow());

        pool.updateHealthStatus("first", HealthStatus.UNKNOWN);

        assertTrue(pool.getSelectableUpstreams().isEmpty());
        assertTrue(pool.selectUpstream(null).isEmpty());
    }

    private Upstream upstream(String id) {
        return new Upstream(id, "localhost", 8080, "http", 1, null);
    }

    private void assertCounter(Upstream upstream, int expected) {
        Object value = upstream.metadata().get(LeastConnectionsStrategy.ACTIVE_CONNECTIONS_KEY);

        assertNotNull(value);
        AtomicInteger counter = assertInstanceOf(AtomicInteger.class, value);
        assertEquals(expected, counter.get());
        assertTrue(counter.get() >= 0);
    }

    private static class CapturingStrategy implements BalancingStrategy {
        private List<Upstream> lastSelectableUpstreams;

        @Override
        public Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx) {
            lastSelectableUpstreams = selectableUpstreams;
            return selectableUpstreams.isEmpty() ? null : selectableUpstreams.get(0);
        }
    }
}
