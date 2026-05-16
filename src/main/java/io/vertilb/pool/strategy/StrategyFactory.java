package io.vertilb.pool.strategy;

import java.util.Locale;

/**
 * Factory for creating supported upstream balancing strategies from configuration values.
 */
public final class StrategyFactory {
    private StrategyFactory() {
    }

    public static BalancingStrategy create(String strategy) {
        String normalized = strategy == null || strategy.isBlank()
            ? "round-robin"
            : strategy.toLowerCase(Locale.ROOT);

        return switch (normalized) {
            case "round-robin" -> new RoundRobinStrategy();
            case "random" -> new RandomStrategy();
            case "ip-hash" -> new IpHashStrategy();
            case "least-connections" -> new LeastConnectionsStrategy();
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        };
    }
}