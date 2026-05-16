package io.vertilb.pool.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertilb.pool.Upstream;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeastConnectionsStrategyTest {
    @Test
    void selectsUpstreamWithFewestActiveConnectionsAndIncrementsIt() {
        Upstream busy = upstream("busy", 3);
        Upstream idle = upstream("idle", 1);

        Upstream selected = new LeastConnectionsStrategy().select(List.of(busy, idle), null);

        assertEquals(idle, selected);
        assertEquals(2, counter(idle).get());
    }

    @Test
    void completionDecrementsCounterWithoutGoingBelowZero() {
        Upstream upstream = upstream("one", 0);
        LeastConnectionsStrategy strategy = new LeastConnectionsStrategy();

        strategy.onRequestCompleted(upstream, null);

        assertEquals(0, counter(upstream).get());
    }

    @Test
    void returnsNullForEmptySelectableList() {
        assertNull(new LeastConnectionsStrategy().select(List.of(), null));
    }

    private Upstream upstream(String id, int activeConnections) {
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put(LeastConnectionsStrategy.ACTIVE_CONNECTIONS_KEY, new AtomicInteger(activeConnections));
        return new Upstream(id, "localhost", 8080, "http", 1, metadata);
    }

    private AtomicInteger counter(Upstream upstream) {
        return (AtomicInteger) upstream.metadata().get(LeastConnectionsStrategy.ACTIVE_CONNECTIONS_KEY);
    }
}
