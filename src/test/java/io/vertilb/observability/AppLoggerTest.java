package io.vertilb.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.vertilb.observability.AppLogger.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class AppLoggerTest {
    @Test
    void levelFromDefaultsBlankOrNullToInfo() {
        assertEquals(Level.INFO, Level.from(null));
        assertEquals(Level.INFO, Level.from(""));
    }

    @Test
    void levelFromAcceptsCaseInsensitiveValues() {
        assertEquals(Level.INFO, Level.from("info"));
        assertEquals(Level.DEBUG, Level.from("DEBUG"));
    }

    @Test
    void infoAllowsErrorWarnAndInfoOnly() {
        assertTrue(Level.INFO.allows(Level.ERROR));
        assertTrue(Level.INFO.allows(Level.WARN));
        assertTrue(Level.INFO.allows(Level.INFO));
        assertFalse(Level.INFO.allows(Level.DEBUG));
        assertFalse(Level.INFO.allows(Level.TRACE));
    }

    @Test
    void debugAllowsThroughDebugOnly() {
        assertTrue(Level.DEBUG.allows(Level.ERROR));
        assertTrue(Level.DEBUG.allows(Level.WARN));
        assertTrue(Level.DEBUG.allows(Level.INFO));
        assertTrue(Level.DEBUG.allows(Level.DEBUG));
        assertFalse(Level.DEBUG.allows(Level.TRACE));
    }

    @Test
    void traceAllowsAllLevels() {
        assertTrue(Level.TRACE.allows(Level.ERROR));
        assertTrue(Level.TRACE.allows(Level.WARN));
        assertTrue(Level.TRACE.allows(Level.INFO));
        assertTrue(Level.TRACE.allows(Level.DEBUG));
        assertTrue(Level.TRACE.allows(Level.TRACE));
    }

    @Test
    void errorAllowsOnlyError() {
        assertTrue(Level.ERROR.allows(Level.ERROR));
        assertFalse(Level.ERROR.allows(Level.WARN));
        assertFalse(Level.ERROR.allows(Level.INFO));
        assertFalse(Level.ERROR.allows(Level.DEBUG));
        assertFalse(Level.ERROR.allows(Level.TRACE));
    }

    @Test
    void suppressesDebugWhenConfiguredInfo() {
        Logger slf4jLogger = mock(Logger.class);
        AppLogger logger = new AppLogger(slf4jLogger, "INFO");

        logger.debug("debug message {}", 1);

        verify(slf4jLogger, never()).debug(eq("debug message {}"), (Object[]) any());
    }

    @Test
    void allowsDebugWhenConfiguredDebug() {
        Logger slf4jLogger = mock(Logger.class);
        AppLogger logger = new AppLogger(slf4jLogger, "DEBUG");

        logger.debug("debug message {}", 1);

        verify(slf4jLogger).debug(eq("debug message {}"), (Object[]) any());
    }
}
