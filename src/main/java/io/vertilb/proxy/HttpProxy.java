package io.vertilb.proxy;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.MultiMap;
import io.vertilb.engine.RequestContext;
import io.vertilb.engine.error.ProxyException;
import io.vertilb.engine.error.UpstreamTimeoutException;
import io.vertilb.pool.Upstream;

import java.util.Locale;
import java.util.Set;

/**
 * HTTP proxy component responsible for forwarding a request to a selected upstream
 * and streaming the response back to the client.
 */
public class HttpProxy {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "transfer-encoding",
        "te",
        "trailer",
        "upgrade",
        "proxy-authorization",
        "proxy-authenticate"
    );

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

    private final HttpClient httpClient;
    private final long requestTimeoutMs;

    /**
     * Creates a proxy with default request timeout.
     *
     * @param vertx Vert.x instance
     */
    public HttpProxy(Vertx vertx) {
        this(vertx, 30_000L);
    }

    /**
     * Creates a proxy with explicit request timeout.
     *
     * @param vertx Vert.x instance
     * @param requestTimeoutMs upstream request timeout in milliseconds
     */
    public HttpProxy(Vertx vertx, long requestTimeoutMs) {
        HttpClientOptions options = new HttpClientOptions()
            .setKeepAlive(true)
            .setReuseAddress(true)
            .setTcpNoDelay(true);

        this.httpClient = vertx.createHttpClient(options);
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * Forwards one client request to one selected upstream.
     *
     * @param ctx request context
     * @param upstream selected upstream
     * @return future completed when forwarding finishes
     */
    public Future<Void> forward(RequestContext ctx, Upstream upstream) {
        Promise<Void> promise = Promise.promise();

        RequestOptions options = new RequestOptions()
            .setMethod(ctx.clientRequest.method())
            .setHost(upstream.host())
            .setPort(upstream.port())
            .setURI(ctx.outboundUri())
            .setSsl("https".equalsIgnoreCase(upstream.protocol()))
            .setTimeout(requestTimeoutMs);

        httpClient.request(options)
            .onFailure(error -> failProxy(ctx, promise, error))
            .onSuccess(outboundRequest -> {
                copyRequestHeaders(ctx, upstream, outboundRequest);
                attachRequestExceptionHandler(ctx, promise, outboundRequest);

                sendRequestBody(ctx, outboundRequest)
                    .onFailure(error -> failProxy(ctx, promise, error))
                    .onSuccess(upstreamResponse -> handleUpstreamResponse(ctx, upstreamResponse, promise));
            });

        return promise.future();
    }

    private void copyRequestHeaders(RequestContext ctx,
                                    Upstream upstream,
                                    HttpClientRequest outboundRequest) {
        MultiMap inboundHeaders = ctx.clientRequest.headers();

        for (String name : inboundHeaders.names()) {
            if (isHopByHopHeader(name)) {
                continue;
            }

            for (String value : inboundHeaders.getAll(name)) {
                outboundRequest.putHeader(name, value);
            }
        }

        outboundRequest.putHeader("Host", upstream.host() + ":" + upstream.port());

        String forwardedFor = resolveForwardedFor(ctx);
        if (forwardedFor != null) {
            outboundRequest.putHeader("X-Forwarded-For", forwardedFor);
        }

        String forwardedProto = ctx.clientRequest.isSSL() ? "https" : "http";
        outboundRequest.putHeader("X-Forwarded-Proto", forwardedProto);

        if (ctx.clientRequest.host() != null) {
            outboundRequest.putHeader("X-Forwarded-Host", ctx.clientRequest.host());
        }

        outboundRequest.putHeader("X-Forwarded-Method", ctx.clientRequest.method().name());
    }

    private void attachRequestExceptionHandler(RequestContext ctx,
                                               Promise<Void> promise,
                                               HttpClientRequest outboundRequest) {
        outboundRequest.exceptionHandler(error -> failProxy(ctx, promise, error));
    }

    private Future<HttpClientResponse> sendRequestBody(RequestContext ctx,
                                                       HttpClientRequest outboundRequest) {
        HttpMethod method = ctx.clientRequest.method();

        if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
            return outboundRequest.send();
        }

        return outboundRequest.send(ctx.clientRequest);
    }

    private void handleUpstreamResponse(RequestContext ctx,
                                        HttpClientResponse upstreamResponse,
                                        Promise<Void> promise) {
        int statusCode = upstreamResponse.statusCode();
        ctx.responseStatusCode = statusCode;

        if (RETRYABLE_STATUS_CODES.contains(statusCode)) {
            drainRetryableResponse(upstreamResponse)
                .onComplete(ignored -> {
                    if (!promise.future().isComplete()) {
                        promise.fail(new ProxyException("Retryable upstream status: " + statusCode));
                    }
                });
            return;
        }

        writeResponseHead(ctx, upstreamResponse);

        upstreamResponse.pipeTo(ctx.response())
            .onSuccess(ignored -> {
                if (!promise.future().isComplete()) {
                    promise.complete();
                }
            })
            .onFailure(error -> failProxy(ctx, promise, error));
    }

    private Future<Void> drainRetryableResponse(HttpClientResponse upstreamResponse) {
        Promise<Void> promise = Promise.promise();

        upstreamResponse.exceptionHandler(promise::fail);
        upstreamResponse.endHandler(ignored -> promise.complete());
        upstreamResponse.resume();

        return promise.future();
    }

    private void writeResponseHead(RequestContext ctx, HttpClientResponse upstreamResponse) {
        if (ctx.response().ended()) {
            return;
        }

        ctx.response().setStatusCode(upstreamResponse.statusCode());

        for (String name : upstreamResponse.headers().names()) {
            if (isHopByHopHeader(name)) {
                continue;
            }

            for (String value : upstreamResponse.headers().getAll(name)) {
                ctx.response().putHeader(name, value);
            }
        }
    }

    private void failProxy(RequestContext ctx, Promise<Void> promise, Throwable error) {
        if (promise.future().isComplete()) {
            return;
        }

        if (isTimeout(error)) {
            promise.fail(new UpstreamTimeoutException("Upstream request timed out", error));
            return;
        }

        promise.fail(error);
    }

    private boolean isHopByHopHeader(String name) {
        return name != null
            && HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean isTimeout(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }

        String message = error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("timeout") || message.contains("timed out");
    }

    private String resolveForwardedFor(RequestContext ctx) {
        String existing = ctx.clientRequest.getHeader("X-Forwarded-For");
        SocketAddress remoteAddress = ctx.clientRequest.remoteAddress();

        if (remoteAddress == null || remoteAddress.host() == null) {
            return existing;
        }

        if (existing == null || existing.isBlank()) {
            return remoteAddress.host();
        }

        return existing + ", " + remoteAddress.host();
    }
}
