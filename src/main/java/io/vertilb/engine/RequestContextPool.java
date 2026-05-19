package io.vertilb.engine;

import io.vertx.core.http.HttpServerRequest;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded request context pool intended for one listener / event-loop usage pattern.
 */
public final class RequestContextPool {
    private final int maxSize;
    private final ArrayDeque<RequestContext> available = new ArrayDeque<>();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong borrowed = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public RequestContextPool(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }

        this.maxSize = maxSize;
    }

    public RequestContext borrow(String poolName, HttpServerRequest request, String rewrittenUri) {
        borrowed.incrementAndGet();

        RequestContext ctx = available.pollLast();
        if (ctx == null) {
            created.incrementAndGet();
            ctx = new RequestContext();
        } else {
            reused.incrementAndGet();
        }

        return ctx.init(poolName, request, rewrittenUri);
    }

    public void release(RequestContext ctx) {
        if (ctx == null) {
            return;
        }

        released.incrementAndGet();
        ctx.reset();

        if (available.size() >= maxSize) {
            dropped.incrementAndGet();
            return;
        }

        available.addLast(ctx);
    }

    public PoolStats stats() {
        return new PoolStats(
            created.get(),
            borrowed.get(),
            reused.get(),
            released.get(),
            dropped.get(),
            available.size()
        );
    }

    public record PoolStats(long created,
                            long borrowed,
                            long reused,
                            long released,
                            long dropped,
                            int available) {
    }
}
