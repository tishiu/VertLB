package io.vertilb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests configuration loading, defaulting, and gateway route validation behavior.
 */
class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsValidJsonConfiguration() throws Exception {
        AppConfig config = ConfigLoader.load(writeConfig(validConfig()).toString());

        assertEquals(1, config.listeners.size());
        assertEquals(1, config.routes.size());
        assertEquals("user-service", config.routes.get(0).poolName);
    }

    @Test
    void rejectsDuplicateListenerPorts() throws Exception {
        String json = validConfig().replace(
            """
              "listeners": [
                {
                  "host": "0.0.0.0",
                  "port": 8080
                }
              ],
            """,
            """
              "listeners": [
                {
                  "host": "0.0.0.0",
                  "port": 8080
                },
                {
                  "host": "127.0.0.1",
                  "port": 8080
                }
              ],
            """
        );

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(writeConfig(json).toString()));
    }

    @Test
    void appliesDocumentedDefaults() throws Exception {
        AppConfig config = ConfigLoader.load(writeConfig(validConfigWithoutOptionalSections()).toString());

        assertEquals(30_000, config.defaults.timeout);
        assertEquals(3, config.defaults.retries.maxAttempts);
        assertEquals(3, config.defaults.retries.retryableStatuses.size());
        assertEquals(100L, config.defaults.retries.backoffMs);
        assertEquals("INFO", config.defaults.logging.level);
        assertEquals("0.0.0.0", config.listeners.get(0).host);
        assertEquals("round-robin", config.pools.get(0).strategy);
        assertEquals("http", config.pools.get(0).upstreams.get(0).protocol);
        assertEquals(1, config.pools.get(0).upstreams.get(0).weight);
        assertEquals(1, config.routes.size());
    }

    @Test
    void rejectsInvalidListenerPort() throws Exception {
        String json = validConfig().replace("\"port\": 8080", "\"port\": 70000");

        assertInvalid(json);
    }

    @Test
    void rejectsRouteReferencingUnknownPool() throws Exception {
        String json = validConfig().replace("\"poolName\": \"user-service\"", "\"poolName\": \"missing-service\"");

        assertInvalid(json);
    }

    @Test
    void rejectsMissingRoutes() throws Exception {
        assertInvalid(configWithRoutes("[]"));
    }

    @Test
    void rejectsRoutePathPrefixWithoutLeadingSlash() throws Exception {
        String json = validConfig().replace("\"pathPrefix\": \"/api/users\"", "\"pathPrefix\": \"api/users\"");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidRouteMethod() throws Exception {
        String json = validConfig().replace("\"methods\": [\"GET\"]", "\"methods\": [\"BREW\"]");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidRouteStripPrefix() throws Exception {
        String json = validConfig().replace("\"stripPrefix\": \"/api\"", "\"stripPrefix\": \"api\"");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidRouteAddPrefix() throws Exception {
        String json = validConfig().replace(
            "\"stripPrefix\": \"/api\"",
            "\"stripPrefix\": \"/api\",\n                  \"addPrefix\": \"v1\""
        );

        assertInvalid(json);
    }

    @Test
    void rejectsDuplicatePoolName() throws Exception {
        assertInvalid(configWithPools("""
                {
                  "name": "user-service",
                  "strategy": "round-robin",
                  "upstreams": [
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9001
                    }
                  ]
                },
                {
                  "name": "user-service",
                  "strategy": "random",
                  "upstreams": [
                    {
                      "id": "user-2",
                      "host": "localhost",
                      "port": 9002
                    }
                  ]
                }
            """));
    }

    @Test
    void rejectsInvalidStrategy() throws Exception {
        String json = validConfig().replace("\"strategy\": \"round-robin\"", "\"strategy\": \"weighted\"");

        assertInvalid(json);
    }

    @Test
    void rejectsDuplicateUpstreamIdInsidePool() throws Exception {
        assertInvalid(configWithPools("""
                {
                  "name": "user-service",
                  "strategy": "round-robin",
                  "upstreams": [
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9001
                    },
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9002
                    }
                  ]
                }
            """));
    }

    @Test
    void rejectsInvalidUpstreamPort() throws Exception {
        String json = validConfig().replace("\"port\": 9001", "\"port\": 0");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidUpstreamProtocol() throws Exception {
        String json = validConfig().replace("\"protocol\": \"http\"", "\"protocol\": \"ftp\"");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidRetryStatus() throws Exception {
        String json = validConfig().replace("\"retryableStatuses\": [502, 503, 504]", "\"retryableStatuses\": [99]");

        assertInvalid(json);
    }

    @Test
    void rejectsNegativeRetryBackoff() throws Exception {
        String json = validConfig().replace("\"backoffMs\": 100", "\"backoffMs\": -1");

        assertInvalid(json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "WARN", "INFO", "DEBUG", "TRACE"})
    void loadsSupportedLoggingLevels(String level) throws Exception {
        String json = validConfig().replace("\"level\": \"INFO\"", "\"level\": \"" + level + "\"");

        AppConfig config = ConfigLoader.load(writeConfig(json).toString());

        assertEquals(level, config.defaults.logging.level);
    }

    @Test
    void normalizesLowercaseLoggingLevel() throws Exception {
        String json = validConfig().replace("\"level\": \"INFO\"", "\"level\": \"debug\"");

        AppConfig config = ConfigLoader.load(writeConfig(json).toString());

        assertEquals("DEBUG", config.defaults.logging.level);
    }

    @Test
    void rejectsInvalidLoggingLevel() throws Exception {
        String json = validConfig().replace("\"level\": \"INFO\"", "\"level\": \"verbose\"");

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidHealthMethod() throws Exception {
        String json = configWithHealthCheck("""
              "enabled": true,
              "method": "BREW"
            """);

        assertInvalid(json);
    }

    @Test
    void rejectsInvalidHealthExpectedStatus() throws Exception {
        String json = configWithHealthCheck("""
              "enabled": true,
              "expectedStatuses": [700]
            """);

        assertInvalid(json);
    }

    @Test
    void rejectsMetricsPortConflictWithListener() throws Exception {
        String json = validConfig().replace("\"port\": 9100", "\"port\": 8080");

        assertInvalid(json);
    }

    private Path writeConfig(String json) throws Exception {
        Path path = tempDir.resolve("config.json");
        Files.writeString(path, json);
        return path;
    }

    private void assertInvalid(String json) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(writeConfig(json).toString()));
    }

    private String configWithRoutes(String routesJson) {
        return """
            {
              "listeners": [
                {
                  "host": "0.0.0.0",
                  "port": 8080
                }
              ],
              "routes": %s,
              "pools": [
                {
                  "name": "user-service",
                  "strategy": "round-robin",
                  "upstreams": [
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9001
                    }
                  ]
                }
              ]
            }
            """.formatted(routesJson);
    }

    private String configWithPools(String poolsJson) {
        return """
            {
              "listeners": [
                {
                  "host": "0.0.0.0",
                  "port": 8080
                }
              ],
              "routes": [
                {
                  "id": "users-route",
                  "pathPrefix": "/api/users",
                  "methods": ["GET"],
                  "poolName": "user-service",
                  "stripPrefix": "/api"
                }
              ],
              "pools": [
            %s
              ]
            }
            """.formatted(poolsJson);
    }

    private String configWithHealthCheck(String healthJson) {
        return validConfig().replace(
            "\"strategy\": \"round-robin\",",
            "\"strategy\": \"round-robin\",\n          \"healthCheck\": {\n%s\n          },".formatted(healthJson)
        );
    }

    private String validConfig() {
        return """
            {
              "listeners": [
                {
                  "host": "0.0.0.0",
                  "port": 8080
                }
              ],
              "routes": [
                {
                  "id": "users-route",
                  "pathPrefix": "/api/users",
                  "methods": ["GET"],
                  "poolName": "user-service",
                  "stripPrefix": "/api"
                }
              ],
              "pools": [
                {
                  "name": "user-service",
                  "strategy": "round-robin",
                  "upstreams": [
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9001,
                      "protocol": "http",
                      "weight": 1
                    }
                  ]
                }
              ],
              "defaults": {
                "timeout": 30000,
                "retries": {
                  "maxAttempts": 3,
                  "retryableStatuses": [502, 503, 504],
                  "backoffMs": 100
                },
                "logging": {
                  "level": "INFO"
                }
              },
              "metrics": {
                "enabled": true,
                "port": 9100,
                "path": "/metrics"
              }
            }
            """;
    }

    private String validConfigWithoutOptionalSections() {
        return """
            {
              "listeners": [
                {
                  "port": 8080
                }
              ],
              "routes": [
                {
                  "pathPrefix": "/api/users",
                  "poolName": "user-service"
                }
              ],
              "pools": [
                {
                  "name": "user-service",
                  "upstreams": [
                    {
                      "id": "user-1",
                      "host": "localhost",
                      "port": 9001
                    }
                  ]
                }
              ]
            }
            """;
    }
}
