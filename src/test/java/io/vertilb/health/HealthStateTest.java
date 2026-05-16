package io.vertilb.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthStateTest {
    @Test
    void successThresholdRequiresConsecutiveSuccesses() {
        HealthState state = new HealthState();

        assertFalse(state.recordSuccess(2));
        assertTrue(state.recordSuccess(2));
        assertEquals(2, state.consecutiveSuccesses());
        assertEquals(0, state.consecutiveFailures());
    }

    @Test
    void failureThresholdRequiresConsecutiveFailures() {
        HealthState state = new HealthState();

        assertFalse(state.recordFailure(2));
        assertTrue(state.recordFailure(2));
        assertEquals(0, state.consecutiveSuccesses());
        assertEquals(2, state.consecutiveFailures());
    }

    @Test
    void successAndFailureResetOppositeCounters() {
        HealthState state = new HealthState();

        state.recordFailure(1);
        state.recordSuccess(1);

        assertEquals(1, state.consecutiveSuccesses());
        assertEquals(0, state.consecutiveFailures());
    }
}
