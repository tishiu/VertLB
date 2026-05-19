package io.vertilb.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertilb.engine.RequestContext;
import io.vertilb.engine.error.ProxyException;
import io.vertilb.pool.Upstream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
class HttpProxyTest {
    @Test
    void hopByHopRequestHeadersAreNotForwardedAndNormalHeadersArePreserved(Vertx vertx,
                                                                           VertxTestContext testContext) {
        AtomicReference<Map<String, List<String>>> backendHeaders = new AtomicReference<>();

        startProxyStack(
            vertx,
            Set.of(503),
            (request, failureHandler) -> {
                backendHeaders.set(snapshotHeaders(request.headers()));
                request.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/plain")
                    .end("ok");
            },
            null
        ).compose(stack -> sendRequest(
            vertx,
            stack.gatewayPort(),
            "/api/users/1?debug=true",
            Map.of(
                "Connection", "keep-alive",
                "Keep-Alive", "timeout=5",
                "X-Custom", "alpha",
                "X-Forwarded-For", "10.0.0.1"
            )
        )).onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                Map<String, List<String>> headers = backendHeaders.get();

                assertEquals(200, response.statusCode());
                assertNotNull(headers);
                assertFalse(headers.containsKey("connection"));
                assertFalse(headers.containsKey("keep-alive"));
                assertEquals(List.of("alpha"), headers.get("x-custom"));
                assertEquals(List.of("10.0.0.1, 127.0.0.1"), headers.get("x-forwarded-for"));
                assertEquals(List.of("http"), headers.get("x-forwarded-proto"));
                assertEquals(List.of("GET"), headers.get("x-forwarded-method"));
                testContext.completeNow();
            }));
    }

    @Test
    void responseHopByHopHeadersAreNotCopied(Vertx vertx, VertxTestContext testContext) {
        startProxyStack(
            vertx,
            Set.of(503),
            (request, failureHandler) -> request.response()
                .setStatusCode(200)
                .putHeader("Connection", "close")
                .putHeader("Keep-Alive", "timeout=5")
                .putHeader("Upgrade", "h2c")
                .putHeader("X-Upstream", "beta")
                .end("upstream"),
            null
        ).compose(stack -> sendRequest(vertx, stack.gatewayPort(), "/api/users/1", Map.of()))
            .onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(200, response.statusCode());
                assertEquals("upstream", response.body());
                assertEquals(List.of("beta"), response.headers().get("x-upstream"));
                assertFalse(response.headers().containsKey("connection"));
                assertFalse(response.headers().containsKey("keep-alive"));
                assertFalse(response.headers().containsKey("upgrade"));
                testContext.completeNow();
            }));
    }

    @Test
    void retryCandidateStatusStillDefersResponseToCaller(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger attempts = new AtomicInteger();

        startProxyStack(
            vertx,
            Set.of(503),
            (request, failureHandler) -> {
                attempts.incrementAndGet();
                request.response()
                    .setStatusCode(503)
                    .putHeader("Connection", "close")
                    .end("retry-me");
            },
            (request, error) -> {
                if (!(error instanceof ProxyException)) {
                    request.response().setStatusCode(598).end(error.getClass().getSimpleName());
                    return;
                }

                if (request.response().ended()) {
                    request.response().setStatusCode(597).end("response-ended-early");
                    return;
                }

                request.response().setStatusCode(599).end("deferred");
            }
        ).compose(stack -> sendRequest(vertx, stack.gatewayPort(), "/api/users/fail503", Map.of()))
            .onFailure(testContext::failNow)
            .onSuccess(response -> testContext.verify(() -> {
                assertEquals(1, attempts.get());
                assertEquals(599, response.statusCode());
                assertEquals("deferred", response.body());
                testContext.completeNow();
            }));
    }

    private Future<ProxyStack> startProxyStack(Vertx vertx,
                                               Set<Integer> retryCandidateStatuses,
                                               BiConsumer<io.vertx.core.http.HttpServerRequest, Promise<Void>> backendHandler,
                                               BiConsumer<io.vertx.core.http.HttpServerRequest, Throwable> failureHandler) {
        HttpServer backend = vertx.createHttpServer()
            .requestHandler(request -> backendHandler.accept(request, Promise.promise()));

        return backend.listen(0, "127.0.0.1")
            .compose(server -> {
                HttpProxy proxy = new HttpProxy(vertx, 5_000L, retryCandidateStatuses);
                Upstream upstream = new Upstream("user-1", "127.0.0.1", server.actualPort(), "http", 1, null);

                return vertx.createHttpServer()
                    .requestHandler(request -> {
                        RequestContext ctx = new RequestContext("user-service", request);
                        ctx.rewrittenUri = request.uri();

                        proxy.forward(ctx, upstream)
                            .onFailure(error -> {
                                if (failureHandler != null) {
                                    failureHandler.accept(request, error);
                                }
                            });
                    })
                    .listen(0, "127.0.0.1")
                    .map(frontend -> new ProxyStack(frontend.actualPort()));
            });
    }

    private Future<ClientResponse> sendRequest(Vertx vertx,
                                               int port,
                                               String uri,
                                               Map<String, String> headers) {
        HttpClient client = vertx.createHttpClient();
        Promise<ClientResponse> promise = Promise.promise();
        RequestOptions options = new RequestOptions()
            .setMethod(HttpMethod.GET)
            .setHost("127.0.0.1")
            .setPort(port)
            .setURI(uri);

        client.request(options)
            .compose(request -> {
                headers.forEach(request::putHeader);
                return request.send();
            })
            .compose(response -> response.body().map(body -> new ClientResponse(
                response.statusCode(),
                body.toString(),
                snapshotHeaders(response.headers())
            )))
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

    private Map<String, List<String>> snapshotHeaders(MultiMap headers) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : headers) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
            snapshot.computeIfAbsent(name, ignored -> new ArrayList<>()).add(entry.getValue());
        }

        return snapshot;
    }

    private record ProxyStack(int gatewayPort) {
    }

    private record ClientResponse(int statusCode, String body, Map<String, List<String>> headers) {
    }
}
