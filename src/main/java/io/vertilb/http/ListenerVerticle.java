package io.vertilb.http;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertilb.config.ListenerConfig;
import io.vertilb.engine.CoreEngine;
import io.vertilb.engine.RequestContext;

/**
 * Vert.x HTTP listener that accepts client requests, creates request contexts,
 * and invokes the core engine.
 */
public class ListenerVerticle extends AbstractVerticle {
    private final ListenerConfig config;
    private final CoreEngine engine;

    /**
     * Creates one HTTP listener bound to one configured upstream pool.
     *
     * @param config listener config containing host, port, and poolName
     * @param engine core request engine
     */
    public ListenerVerticle(ListenerConfig config, CoreEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        String host = config.host == null || config.host.isBlank()
            ? "0.0.0.0"
            : config.host;

        vertx.createHttpServer()
            .requestHandler(request -> {
                RequestContext ctx = new RequestContext(config.poolName, request);

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