package io.vertilb.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertilb.config.ListenerConfig;
import io.vertilb.config.RouteConfig;
import io.vertilb.engine.CoreEngine;
import io.vertilb.engine.RetryPolicy;
import io.vertilb.gateway.GatewayRouter;
import io.vertilb.http.ListenerVerticle;
import io.vertilb.observability.AppLogger;
import io.vertilb.observability.MetricsCollector;
import io.vertilb.pool.Upstream;
import io.vertilb.pool.UpstreamPool;
import io.vertilb.pool.strategy.RoundRobinStrategy;
import io.vertilb.proxy.HttpProxy;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class GatewayRequestPathIntegrationTest {
    private static final String POOL_NAME = "user-service";

    @Test
    void getApiUsersReturnsBackendResponse(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/users/1"))
            .onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertEquals("backend:/users/1", response.body());
                testContext.completeNow();
            }));
    }

    @Test
    void stripPrefixWorksBeforeForwardingToBackend(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/users/1")
                .map(response -> new StackResponse(stack, response)))
            .onFailure(testContext::failNow)
            .onSuccess(result -> testContext.verify(() -> {
                assertEquals(200, result.response().statusCode());
                assertEquals("/users/1", result.stack().backendUris().peek());
                testContext.completeNow();
            }));
    }

    @Test
    void queryStringIsPreservedWhenForwardingToBackend(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/users/1?debug=true")
                .map(response -> new StackResponse(stack, response)))
            .onFailure(testContext::failNow)
            .onSuccess(result -> testContext.verify(() -> {
                assertEquals(200, result.response().statusCode());
                assertEquals("/users/1?debug=true", result.stack().backendUris().peek());
                testContext.completeNow();
            }));
    }

    @Test
    void unknownRouteReturns404(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/orders/1"))
            .onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(404, response.statusCode());
                assertEquals("No route matched", response.body());
                testContext.completeNow();
            }));
    }

    @Test
    void backend503ReturnsFinal503AfterRetriesAreExhausted(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/users/fail503")
                .map(response -> new StackResponse(stack, response)))
            .onFailure(testContext::failNow)
            .onSuccess(result -> testContext.verify(() -> {
                assertEquals(503, result.response().statusCode());
                assertEquals("Bad Gateway", result.response().body());
                assertEquals(2, result.stack().fail503Requests().get());
                testContext.completeNow();
            }));
    }

    @Test
    void metricsAreRecordedAfterSuccessfulRequest(Vertx vertx, VertxTestContext testContext) {
        startStack(vertx)
            .compose(stack -> get(vertx, stack.gatewayPort(), "/api/users/1")
                .compose(response -> nextTick(vertx).map(stack)))
            .onFailure(testContext::failNow)
            .onSuccess(stack -> testContext.verify(() -> {
                MetricsCollector.MetricsSnapshot snapshot = stack.metrics().snapshot();

                assertTrue(snapshot.totalRequests > 0);
                assertTrue(snapshot.requestsByPool.containsKey(POOL_NAME));
                assertTrue(snapshot.statusCodeBuckets.containsKey("200"));
                testContext.completeNow();
            }));
    }

    private Future<TestStack> startStack(Vertx vertx) {
        Queue<String> backendUris = new ConcurrentLinkedQueue<>();
        AtomicInteger fail503Requests = new AtomicInteger();
        HttpServer backend = vertx.createHttpServer()
            .requestHandler(request -> {
                backendUris.add(request.uri());

                if ("/users/fail503".equals(request.path())) {
                    fail503Requests.incrementAndGet();
                    request.response()
                        .setStatusCode(503)
                        .end("unavailable");
                    return;
                }

                request.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/plain")
                    .end("backend:" + request.path());
            });

        return backend.listen(0, "127.0.0.1")
            .compose(server -> startGateway(vertx, server, backendUris, fail503Requests));
    }

    private Future<TestStack> startGateway(Vertx vertx,
                                           HttpServer backend,
                                           Queue<String> backendUris,
                                           AtomicInteger fail503Requests) {
        int gatewayPort;

        try {
            gatewayPort = unusedPort();
        } catch (IOException error) {
            return Future.failedFuture(error);
        }

        MetricsCollector metrics = new MetricsCollector();
        Upstream upstream = new Upstream(
            "user-1",
            "127.0.0.1",
            backend.actualPort(),
            "http",
            1,
            null
        );
        UpstreamPool pool = new UpstreamPool(POOL_NAME, List.of(upstream), new RoundRobinStrategy());

        RetryPolicy retryPolicy = new RetryPolicy(2, Set.of(503), 0);
        HttpProxy proxy = new HttpProxy(vertx, 5_000L, retryPolicy.retryableStatuses());
        CoreEngine engine = new CoreEngine(
            Map.of(POOL_NAME, pool),
            proxy,
            new AppLogger("ERROR"),
            metrics,
            retryPolicy,
            vertx
        );

        ListenerConfig listener = new ListenerConfig();
        listener.host = "127.0.0.1";
        listener.port = gatewayPort;

        RouteConfig route = new RouteConfig();
        route.pathPrefix = "/api/users";
        route.methods = List.of("GET");
        route.poolName = POOL_NAME;
        route.stripPrefix = "/api";

        GatewayRouter router = new GatewayRouter(List.of(route));

        return vertx.deployVerticle(new ListenerVerticle(listener, router, engine))
            .map(deploymentId -> new TestStack(
                gatewayPort,
                backendUris,
                fail503Requests,
                metrics
            ));
    }

    private Future<ClientResponse> get(Vertx vertx, int port, String uri) {
        HttpClient client = vertx.createHttpClient();
        Promise<ClientResponse> promise = Promise.promise();
        RequestOptions options = new RequestOptions()
            .setHost("127.0.0.1")
            .setPort(port)
            .setURI(uri);

        client.request(options)
            .compose(request -> request.send())
            .compose(response -> response.body()
                .map(body -> new ClientResponse(response.statusCode(), body.toString())))
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

    private Future<Void> nextTick(Vertx vertx) {
        Promise<Void> promise = Promise.promise();
        vertx.runOnContext(ignored -> promise.complete());
        return promise.future();
    }

    private int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record TestStack(int gatewayPort,
                             Queue<String> backendUris,
                             AtomicInteger fail503Requests,
                             MetricsCollector metrics) {
    }

    private record ClientResponse(int statusCode, String body) {
    }

    private record StackResponse(TestStack stack, ClientResponse response) {
    }
}
