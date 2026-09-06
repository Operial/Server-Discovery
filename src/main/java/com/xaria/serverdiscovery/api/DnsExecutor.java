package com.xaria.serverdiscovery.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool dedicated solely to DNS resolution ({@code InetAddress.getByName}),
 * kept deliberately separate from {@link NetworkExecutor} (which does the
 * actual socket connect-and-ping work).
 *
 * <p>The reason for the split: {@code InetAddress.getByName} has no timeout
 * of its own, so a small number of addresses with slow or non-responding DNS
 * can occupy a worker thread far longer than any real ping should take -
 * confirmed in practice, where it caused the vast majority of a ~2200-server
 * scan to end up marked offline (not because they were actually offline, but
 * because the small, shared pool got saturated by a handful of hung DNS
 * lookups early on, and every server queued behind them timed out without
 * ever actually being attempted). Giving DNS its own, more generously sized
 * pool means a cluster of bad addresses can only ever block other DNS
 * lookups - it can no longer starve the pool that everything else depends on
 * to actually get pinged.
 */
public final class DnsExecutor {

    private static final int POOL_SIZE = 128;
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    public static final ExecutorService INSTANCE = Executors.newFixedThreadPool(POOL_SIZE, threadFactory());

    private DnsExecutor() {
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "serverdiscovery-dns-" + THREAD_COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
