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
        consecutiveSuccesses++;
        consecutiveFailures = 0;

        return consecutiveSuccesses >= Math.max(1, successThreshold);
    }

    /**
     * Records a failed health probe.
     *
     * @param failureThreshold threshold required to become unhealthy
     * @return true when the failure threshold is reached
     */
    public boolean recordFailure(int failureThreshold) {
        consecutiveFailures++;
        consecutiveSuccesses = 0;

        return consecutiveFailures >= Math.max(1, failureThreshold);
    }

    public int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }
}
