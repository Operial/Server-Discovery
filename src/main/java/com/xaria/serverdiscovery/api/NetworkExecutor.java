package com.xaria.serverdiscovery.api;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A dedicated, bounded pool for everything this mod does over the network -
 * the (potentially thousands of) blocking SLP pings a directory load
 * triggers, plus the directory/logo/background HTTP fetches. Originally
 * just for pings (hence the class being named that in an earlier version);
 * widened after log evidence showed HTTP fetches sitting on the JVM's
 * shared {@code ForkJoinPool.commonPool()} for minutes behind unrelated
 * mods' own network calls (auth/profile lookups, crash-report uploads,
 * etc.) that were also timing out in the same session - the game has a lot
 * of mods all fighting over that one shared pool, and blocking socket work
 * doesn't belong on it regardless.
 *
 * <p>The other reason this exists: firing every ping at once (unbounded
 * concurrency) is itself part of what made results unreliable - do enough
 * of them at the same time and individual pings queue up behind OS/network
 * resource limits, which shows up as inflated (and wrong) ping numbers.
 * A fixed size lets many servers get checked in parallel without either
 * problem.
 */
public final class NetworkExecutor {

    private static final int POOL_SIZE = 64;
    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    public static final ExecutorService INSTANCE = Executors.newFixedThreadPool(POOL_SIZE, threadFactory());

    private NetworkExecutor() {
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "serverdiscovery-net-" + THREAD_COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
