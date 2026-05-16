package io.vertilb.pool.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertilb.pool.Upstream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test skeleton for round-robin strategy ordering and wraparound behavior.
 */
class RoundRobinStrategyTest {
    @Test
    void selectsFirstSelectableUpstreamInitially() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        List<Upstream> upstreams = List.of(upstream("one"), upstream("two"));

        assertEquals(upstreams.get(0), strategy.select(upstreams, null));
    }

    @Test
    void advancesSelectionOnEachRequest() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        List<Upstream> upstreams = List.of(upstream("one"), upstream("two"));

        strategy.select(upstreams, null);

        assertEquals(upstreams.get(1), strategy.select(upstreams, null));
    }

    @Test
    void wrapsAroundAfterLastHealthyUpstream() {
        RoundRobinStrategy strategy = new RoundRobinStrategy();
        List<Upstream> upstreams = List.of(upstream("one"), upstream("two"));

        strategy.select(upstreams, null);
        strategy.select(upstreams, null);

        assertEquals(upstreams.get(0), strategy.select(upstreams, null));
    }

    @Test
    void returnsNullForEmptySelectableList() {
        assertNull(new RoundRobinStrategy().select(List.of(), null));
    }

    private Upstream upstream(String id) {
        return new Upstream(id, "localhost", 8080, "http", 1, null);
    }
}
