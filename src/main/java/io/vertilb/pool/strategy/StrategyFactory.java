package io.vertilb.pool.strategy;

/**
 * Factory for creating supported upstream balancing strategies from configuration values.
 */
public final class StrategyFactory {
    private StrategyFactory() {
    }

    /**
     * Creates a balancing strategy by name.
     *
     * @param strategy configured strategy name
     * @return matching balancing strategy
     */
    public static BalancingStrategy create(String strategy) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
