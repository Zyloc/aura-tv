package com.aura.tvprobe;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared, bounded, auto-shrinking daemon thread pools for Aura TV.
 * Replaces 8 separate per-class ExecutorServices and scales down to 0 idle
 * worker threads after 30s of inactivity to keep TV footprint razor-thin.
 */
public final class AuraExecutors {

    private static final ExecutorService IO_POOL = buildAutoShrinkingPool(6, 12, "aura-io-");
    private static final ExecutorService SERIAL_POOL = buildAutoShrinkingPool(1, 1, "aura-bg-");

    private AuraExecutors() {}

    public static ExecutorService io() {
        return IO_POOL;
    }

    public static ExecutorService serial() {
        return SERIAL_POOL;
    }

    public static void awaitAll(List<Future<?>> futures, long timeoutMs) {
        if (futures == null || futures.isEmpty()) return;
        long deadline = System.currentTimeMillis() + Math.max(50L, timeoutMs);
        for (Future<?> f : futures) {
            long rem = Math.max(10L, deadline - System.currentTimeMillis());
            try { f.get(rem, TimeUnit.MILLISECONDS); } catch (Exception ignored) { f.cancel(true); }
        }
    }

    private static ExecutorService buildAutoShrinkingPool(int core, int max, String prefix) {
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
            core, max, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), daemonFactory(prefix));
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
