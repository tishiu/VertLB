package io.vertilb.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertilb.pool.strategy.LeastConnectionsStrategy;
import io.vertilb.pool.strategy.RoundRobinStrategy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Test skeleton for upstream pool selection, health filtering, and health updates.
 */
class UpstreamPoolTest {
    @Test
    void selectsHealthyUpstreamWhenAvailable() {
        // TODO
    }

    @Test
    void returnsEmptyWhenNoHealthyUpstreamsExist() {
        // TODO
    }

    @Test
    void updatesUpstreamHealthStatusById() {
        // TODO
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
        assertEquals(List.of(second), pool.getHealthyUpstreams());
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
}
