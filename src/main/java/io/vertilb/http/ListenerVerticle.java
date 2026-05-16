package io.vertilb.http;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertilb.config.ListenerConfig;
import io.vertilb.engine.CoreEngine;
import io.vertilb.engine.RequestContext;
import io.vertilb.gateway.GatewayRouter;
import io.vertilb.gateway.RouteDecision;

/**
 * Vert.x HTTP listener that accepts client requests, resolves gateway routes,
 * creates request contexts, and invokes the core engine.
 */
public class ListenerVerticle extends AbstractVerticle {
    private final ListenerConfig config;
    private final GatewayRouter router;
    private final CoreEngine engine;

    public ListenerVerticle(ListenerConfig config, GatewayRouter router, CoreEngine engine) {
        this.config = config;
        this.router = router;
        this.engine = engine;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        String host = config.host == null || config.host.isBlank()
            ? "0.0.0.0"
            : config.host;

        vertx.createHttpServer()
            .requestHandler(request -> {
                RouteDecision decision;

                try {
                    decision = router.resolve(request);
                } catch (Exception routeError) {
                    if (!request.response().ended()) {
                        request.response()
                            .setStatusCode(404)
                            .putHeader("Content-Type", "text/plain")
                            .end("No route matched");
                    }
                    return;
                }

                RequestContext ctx = new RequestContext(decision.poolName(), request);
                ctx.rewrittenUri = decision.rewrittenUri();

                engine.handleRequest(ctx)
                    .onFailure(error -> {
                        if (!request.response().ended()) {
                            request.response()
                                .setStatusCode(502)
                                .putHeader("Content-Type", "text/plain")
                                .end("Bad Gateway");
                        }
                    });
            })
            .listen(config.port, host)
            .onSuccess(server -> startPromise.complete())
            .onFailure(startPromise::fail);
    }
}
