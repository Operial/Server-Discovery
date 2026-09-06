package com.xaria.serverdiscovery.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared HTTP client for the mod's outbound requests (directory JSON, logo
 * images, the default thumbnail).
 *
 * <p>This used to share {@link NetworkExecutor} with the thousands of
 * blocking SLP-ping sockets a full scan opens - which turned out to be a
 * real bug, confirmed by a log where the directory fetch, the default
 * thumbnail fetch, AND the custom API all failed with "connect timed out"
 * at the exact same moment, right as ~2200 pings were saturating that pool.
 * {@code HttpClient} is asynchronous, but it still needs its provided
 * executor to run its own completion callbacks (e.g. "the connection
 * succeeded, continue") - with every worker thread permanently tied up in
 * a blocking {@code Socket} call from the ping scan, those callbacks could
 * queue behind thousands of them and never get a turn within the configured
 * timeout, even though the actual TCP connection might have gone through
 * fine. A handful of HTTP fetches (two directory sources, a thumbnail, a
 * few images at a time) never need more than a handful of threads, so this
 * gets its own small pool, fully isolated from ping/DNS traffic.
 */
public final class HttpUtil {

    private static final int POOL_SIZE = 8;
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE, threadFactory());

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .executor(EXECUTOR)
            .build();

    private HttpUtil() {
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "serverdiscovery-http-" + THREAD_COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static CompletableFuture<String> fetchText(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException(url + " returned HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }

    public static CompletableFuture<byte[]> fetchBytes(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new RuntimeException(url + " returned HTTP " + response.statusCode());
                    }
                    return response.body();
                });
    }
}
