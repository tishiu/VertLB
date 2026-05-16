package io.vertilb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test skeleton for configuration loading, defaulting, and validation behavior.
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
        assertEquals(1, config.defaults.retries.maxAttempts);
        assertEquals("/metrics", config.metrics.path);
        assertEquals(1, config.routes.size());
    }

    @Test
    void rejectsRouteReferencingUnknownPool() throws Exception {
        String json = validConfig().replace("\"poolName\": \"user-service\"", "\"poolName\": \"missing-service\"");

        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(writeConfig(json).toString()));
    }

    private Path writeConfig(String json) throws Exception {
        Path path = tempDir.resolve("config.json");
        Files.writeString(path, json);
        return path;
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
