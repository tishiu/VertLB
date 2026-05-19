package io.vertilb.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertilb.config.ListenerConfig;
import io.vertilb.engine.CoreEngine;
import io.vertilb.engine.RequestContext;
import io.vertilb.engine.RequestContextFactory;
import io.vertilb.engine.RequestContextPool;
import io.vertilb.engine.PooledRequestContextFactory;
import io.vertilb.engine.RetryPolicy;
import io.vertilb.gateway.GatewayRouter;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class ListenerVerticleTest {
    @Test
    void poolDisabledPathStillWorks(Vertx vertx, VertxTestContext testContext) throws Exception {
        RequestContextFactory factory = new RequestContextFactory() {
            @Override
            public RequestContext create(String poolName, io.vertx.core.http.HttpServerRequest request, String rewrittenUri) {
                RequestContext ctx = new RequestContext(poolName, request);
                ctx.rewrittenUri = rewrittenUri;
                return ctx;
            }

            @Override
            public void release(RequestContext ctx) {
            }
        };

        startListener(vertx, new CompletingEngine(), factory)
            .compose(port -> sendRequest(vertx, port, "/api/users/1"))
            .onFailure(testContext::failNow)
            .onSuccess(status -> testContext.verify(() -> {
                assertEquals(200, status);
                testContext.completeNow();
            }));
    }

    @Test
    void poolEnabledPathReleasesContextAfterCompletion(Vertx vertx, VertxTestContext testContext) throws Exception {
        RequestContextPool pool = new RequestContextPool(4);
        PooledRequestContextFactory factory = new PooledRequestContextFactory(pool);
        ControlledEngine engine = new ControlledEngine();

        startListener(vertx, engine, factory)
            .compose(port -> {
                Future<Integer> clientCall = sendRequestWithoutAwaiting(vertx, port, "/api/users/1");

                return engine.started.future().compose(ignored -> {
                    testContext.verify(() -> {
                        assertEquals(0, pool.stats().available());
                        assertEquals(1L, pool.stats().borrowed());
                    });

                    RequestContext borrowed = engine.lastContext.get();
                    testContext.verify(() -> assertSame(borrowed, engine.lastContext.get()));

                    engine.completeSuccess();
                    return clientCall;
                });
            })
            .onFailure(testContext::failNow)
            .onSuccess(status -> testContext.verify(() -> {
                assertEquals(200, status);
                assertEquals(1, pool.stats().available());
                assertEquals(1L, pool.stats().released());
                testContext.completeNow();
            }));
    }

    private Future<Integer> startListener(Vertx vertx,
                                          CoreEngine engine,
                                          RequestContextFactory factory) throws IOException {
        ListenerConfig listener = new ListenerConfig();
        listener.host = "127.0.0.1";
        listener.port = unusedPort();

        return vertx.deployVerticle(new ListenerVerticle(
            listener,
            new GatewayRouter(List.of(route())),
            engine,
            factory
        )).map(ignored -> listener.port);
    }

    private Future<Integer> sendRequest(Vertx vertx, int port, String uri) {
        return sendRequestWithoutAwaiting(vertx, port, uri);
    }

    private Future<Integer> sendRequestWithoutAwaiting(Vertx vertx, int port, String uri) {
        HttpClient client = vertx.createHttpClient();
        Promise<Integer> promise = Promise.promise();

        client.request(new RequestOptions()
                .setHost("127.0.0.1")
                .setPort(port)
                .setURI(uri))
            .compose(request -> request.send())
            .compose(response -> response.body().map(ignored -> response.statusCode()))
            .onComplete(result -> {
                client.close();
                if (result.succeeded()) {
                    promise.complete(result.result());
                } else {
                    promise.fail(result.cause());
                }
            });

        return promise.future();
    }

    private io.vertilb.config.RouteConfig route() {
        io.vertilb.config.RouteConfig route = new io.vertilb.config.RouteConfig();
        route.pathPrefix = "/api/users";
        route.methods = List.of("GET");
        route.poolName = "user-service";
        route.stripPrefix = "/api";
        return route;
    }

    private int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class CompletingEngine extends CoreEngine {
        CompletingEngine() {
            super(Map.of(), null, new AppLogger("ERROR"), new MetricsCollector(), new RetryPolicy(1, Set.of(503), 0));
        }

        @Override
        public Future<Void> handleRequest(RequestContext ctx) {
            ctx.response().setStatusCode(200).end("ok");
            return Future.succeededFuture();
        }
    }

    private static final class ControlledEngine extends CoreEngine {
        private final Promise<Void> started = Promise.promise();
        private final Promise<Void> promise = Promise.promise();
        private final AtomicReference<RequestContext> lastContext = new AtomicReference<>();

        ControlledEngine() {
            super(Map.of(), null, new AppLogger("ERROR"), new MetricsCollector(), new RetryPolicy(1, Set.of(503), 0));
        }

        @Override
        public Future<Void> handleRequest(RequestContext ctx) {
            lastContext.set(ctx);
            started.tryComplete();
            return promise.future();
        }

        void completeSuccess() {
            RequestContext ctx = lastContext.get();
            ctx.response().setStatusCode(200).end("ok");
            promise.tryComplete();
        }
    }
}
