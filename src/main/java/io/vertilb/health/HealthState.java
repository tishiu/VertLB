package io.vertilb.health;

/**
 * Tracks consecutive health-check successes and failures for threshold-based health transitions.
 */
public class HealthState {
    private int consecutiveSuccesses;
    private int consecutiveFailures;

    /**
     * Records a successful health probe.
     *
     * @param successThreshold threshold required to become healthy
     * @return true when the success threshold is reached
     */
    public boolean recordSuccess(int successThreshold) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Records a failed health probe.
     *
     * @param failureThreshold threshold required to become unhealthy
     * @return true when the failure threshold is reached
     */
    public boolean recordFailure(int failureThreshold) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
