package io.vertilb.config;

/**
 * Loads, validates, cross-validates, and default-fills application configuration from JSON.
 */
public final class ConfigLoader {
    private ConfigLoader() {
    }

    /**
     * Reads a configuration file and returns a validated application configuration.
     *
     * @param path path to a JSON configuration file
     * @return validated application configuration
     * @throws IllegalArgumentException when the configuration is missing or invalid
     */
    public static AppConfig load(String path) {
        // TODO
        throw new UnsupportedOperationException("TODO");
    }
}
