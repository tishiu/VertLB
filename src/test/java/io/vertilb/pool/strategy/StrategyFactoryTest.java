package io.vertilb.pool.strategy;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StrategyFactoryTest {
    @Test
    void createsRoundRobinByDefault() {
        assertInstanceOf(RoundRobinStrategy.class, StrategyFactory.create(null));
        assertInstanceOf(RoundRobinStrategy.class, StrategyFactory.create(""));
    }

    @Test
    void createsSupportedStrategiesByName() {
        assertInstanceOf(RoundRobinStrategy.class, StrategyFactory.create("round-robin"));
        assertInstanceOf(RandomStrategy.class, StrategyFactory.create("random"));
        assertInstanceOf(IpHashStrategy.class, StrategyFactory.create("ip-hash"));
        assertInstanceOf(LeastConnectionsStrategy.class, StrategyFactory.create("least-connections"));
    }

    @Test
    void rejectsUnknownStrategy() {
        assertThrows(IllegalArgumentException.class, () -> StrategyFactory.create("weighted"));
    }
}
