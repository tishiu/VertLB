package io.vertilb.config;

/**
 * Configuration for one HTTP listener and the pool that should receive its traffic.
 */
public class ListenerConfig {
    public String host;
    public int port;
    public String poolName;
}
