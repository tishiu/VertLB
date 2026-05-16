package io.vertilb.pool.strategy;

import io.vertilb.engine.RequestContext;
import io.vertilb.pool.Upstream;
import java.util.List;

/**
 * Selects upstream based on client IP hash.
 */
public class IpHashStrategy implements BalancingStrategy {
    @Override
    public Upstream select(List<Upstream> selectableUpstreams, RequestContext ctx) {
        if (selectableUpstreams == null || selectableUpstreams.isEmpty()) {
            return null;
        }

        if (ctx == null || ctx.clientRequest == null || ctx.clientRequest.remoteAddress() == null) {
            return selectableUpstreams.get(0);
        }

        String ip = ctx.clientRequest.remoteAddress().host();
        if (ip == null || ip.isBlank()) {
            return selectableUpstreams.get(0);
        }

        int index = Math.floorMod(ip.hashCode(), selectableUpstreams.size());
        return selectableUpstreams.get(index);
    }
}
