package io.vertilb.config;

import java.util.Map;

/**
 * Configuration for an upstream endpoint that can be converted into runtime pool state.
 */
public class UpstreamConfig {
    public String id;
    public String host;
    public int port;
    public String protocol;
    public Integer weight;
    public Map<String, Object> metadata;
}
