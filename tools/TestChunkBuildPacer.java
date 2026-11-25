package io.github.imcinq.puremetal.perf;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless regression tests for {@link ChunkBuildPacer} and {@link EarlyTerrainUpload}:
 * <ul>
 *   <li>Apple Silicon P-core + thermal scaling (Nominal / Fair / Serious+) and fallback when P-core count is 0</li>
 *   <li>Metal-backend gating and loading-screen cap lift</li>
 *   <li>500 ms serialized thermal poll caching and 5 s cooling hysteresis</li>
 *   <li>Multithreaded dispatch protocol (no lost wakeups, bounded concurrency)</li>
 *   <li>Regression check that an NPE inside doTask (pack held) does not spin-retry or drain buffer packs</li>
 * </ul>
 */
public final class TestChunkBuildPacer {
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void testScaling() {
        // Degenerate / tiny counts
        require(ChunkBuildPacer.computeLimit(1, 1, 0) == 1, "1-core nominal");
        require(ChunkBuildPacer.computeLimit(1, 1, 2) == 1, "1-core serious");
        require(ChunkBuildPacer.computeLimit(2, 2, 0) == 1, "2-core nominal capped at cpus-1");
        require(ChunkBuildPacer.computeLimit(2, 4, 0) == 2, "2P/4c nominal");

        // 4 P-core Macs (M1-M5 base/Air, 8-10 total cores): 4 / 3 / 2
        require(ChunkBuildPacer.computeLimit(4, 8, 0) == 4, "4P/8c nominal");
        require(ChunkBuildPacer.computeLimit(4, 8, 1) == 3, "4P/8c fair");
        require(ChunkBuildPacer.computeLimit(4, 8, 2) == 2, "4P/8c serious");
        require(ChunkBuildPacer.computeLimit(4, 10, 0) == 4, "4P/10c nominal (M4/M5 Air)");
        require(ChunkBuildPacer.computeLimit(4, 10, 1) == 3, "4P/10c fair (M4/M5 Air)");
        require(ChunkBuildPacer.computeLimit(4, 10, 2) == 2, "4P/10c serious (M4/M5 Air)");
        require(ChunkBuildPacer.computeLimit(4, 10, 3) == 2, "4P/10c critical");

        // 6 P-core Mac (M1 Pro 8-core, M3 Pro 11/12-core): 6 / 4 / 3
        require(ChunkBuildPacer.computeLimit(6, 8, 0) == 6, "6P/8c nominal");
        require(ChunkBuildPacer.computeLimit(6, 8, 1) == 4, "6P/8c fair");
        require(ChunkBuildPacer.computeLimit(6, 8, 2) == 3, "6P/8c serious");

        // 8 P-core Mac (M1 Pro 10c, M2/M4 Pro 12c): 8 / 5 / 4
        require(ChunkBuildPacer.computeLimit(8, 10, 0) == 8, "8P/10c nominal");
        require(ChunkBuildPacer.computeLimit(8, 10, 1) == 5, "8P/10c fair");
        require(ChunkBuildPacer.computeLimit(8, 10, 2) == 4, "8P/10c serious");

        // 10 P-core Mac (M3/M4 Pro/Max 14c): 10 / 7 / 5
        require(ChunkBuildPacer.computeLimit(10, 14, 0) == 10, "10P/14c nominal");
        require(ChunkBuildPacer.computeLimit(10, 14, 1) == 7, "10P/14c fair");
        require(ChunkBuildPacer.computeLimit(10, 14, 2) == 5, "10P/14c serious");

        // 12 P-core Mac (M3/M4 Max 16c): 12 / 8 / 6
        require(ChunkBuildPacer.computeLimit(12, 16, 0) == 12, "12P/16c nominal");
        require(ChunkBuildPacer.computeLimit(12, 16, 1) == 8, "12P/16c fair");
        require(ChunkBuildPacer.computeLimit(12, 16, 2) == 6, "12P/16c serious");

        // 16 P-core Mac (M1/M2 Ultra 20c): 16 / 11 / 8
        require(ChunkBuildPacer.computeLimit(16, 20, 0) == 16, "16P/20c nominal");
        require(ChunkBuildPacer.computeLimit(16, 20, 1) == 11, "16P/20c fair");
        require(ChunkBuildPacer.computeLimit(16, 20, 2) == 8, "16P/20c serious");

        // 20 P-core Mac (M3 Ultra 28c): 20 / 13 / 10
        require(ChunkBuildPacer.computeLimit(20, 28, 0) == 20, "20P/28c nominal");
        require(ChunkBuildPacer.computeLimit(20, 28, 1) == 13, "20P/28c fair");
        require(ChunkBuildPacer.computeLimit(20, 28, 2) == 10, "20P/28c serious");

        // Fallback when sysctl P-core count is unavailable (0): estimates totalCpus / 2
        require(ChunkBuildPacer.computeLimit(0, 8, 0) == 4, "fallback 0P/8c nominal");
        require(ChunkBuildPacer.computeLimit(0, 8, 1) == 3, "fallback 0P/8c fair");
        require(ChunkBuildPacer.computeLimit(0, 8, 2) == 2, "fallback 0P/8c serious");
    }

    private static void testBackendGateLoadingAndHysteresis() {
        AtomicInteger pCores = new AtomicInteger(4);
        AtomicInteger cpus = new AtomicInteger(10);
        AtomicBoolean active = new AtomicBoolean(false);
        AtomicBoolean loading = new AtomicBoolean(false);
        AtomicInteger thermal = new AtomicInteger(1);
        AtomicInteger polls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000_000_000L);

        ChunkBuildPacer.setHooksForTest(pCores::get, cpus::get, active::get, loading::get, () -> {
            polls.incrementAndGet();
            return thermal.get();
        }, clock::get);
        EarlyTerrainUpload.setMetalActiveForTest(active::get);

        // Inactive (OpenGL/Vulkan fallback): pacer returns MAX_VALUE without polling thermal, early upload off.
        require(ChunkBuildPacer.maxConcurrentBuilds() == Integer.MAX_VALUE, "inactive backend must not cap");
        require(!EarlyTerrainUpload.active(), "early terrain upload must be off on non-Metal");
        require(polls.get() == 0, "must not poll thermal when inactive");

        // Active Metal backend during loading screen: cap is lifted without polling thermal.
        active.set(true);
        loading.set(true);
        require(EarlyTerrainUpload.active(), "early terrain upload active on Metal");
        require(ChunkBuildPacer.maxConcurrentBuilds() == Integer.MAX_VALUE, "loading screen must lift cap");
        require(polls.get() == 0, "must not poll thermal during loading screen");

        // Gameplay resumes: polls once and steps down immediately to Fair (3 on 4P/10c).
        loading.set(false);
        require(ChunkBuildPacer.maxConcurrentBuilds() == 3, "Fair 4P/10c should be 3");
        require(polls.get() == 1, "expected 1 poll");

        // Within 500 ms cache window: no new poll.
        thermal.set(2);
        clock.addAndGet(400_000_000L);
        require(ChunkBuildPacer.maxConcurrentBuilds() == 3, "cached within 500ms");
        require(polls.get() == 1, "still 1 poll within 500ms");

        // After 500 ms: step-down to Serious (2) is immediate.
        clock.addAndGet(200_000_000L);
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "Serious 4P/10c should step down immediately to 2");
        require(polls.get() == 2, "expected 2 polls");

        // Cooling back to Nominal (0): must hold Serious (2) during < 5 s hysteresis.
        thermal.set(0);
        clock.addAndGet(600_000_000L); // starts coolerSinceNs
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "cooling starts timer, still 2");
        clock.addAndGet(4_000_000_000L); // 4 s elapsed (< 5 s)
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "must still hold 2 at 4s");

        // Brief thermal bounce back to 2 resets cooling timer.
        thermal.set(2);
        clock.addAndGet(600_000_000L);
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "bounce resets cooling");
        thermal.set(0);
        clock.addAndGet(600_000_000L); // restarts coolerSinceNs
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "restart timer, still 2");
        clock.addAndGet(4_000_000_000L); // only 4 s since restart
        require(ChunkBuildPacer.maxConcurrentBuilds() == 2, "must hold 2 after reset");

        // Sustained >= 5 s cooling relaxes back to Nominal (4).
        clock.addAndGet(1_100_000_000L);
        require(ChunkBuildPacer.maxConcurrentBuilds() == 4, "should relax to 4 after 5s sustained cooling");
    }

    private static void testDispatchProtocolAndNpeSafety() throws Exception {
        AtomicInteger pCores = new AtomicInteger(4);
        AtomicInteger cpus = new AtomicInteger(10);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean loading = new AtomicBoolean(false);
        AtomicInteger thermal = new AtomicInteger(0);
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        ChunkBuildPacer.setHooksForTest(pCores::get, cpus::get, active::get, loading::get, thermal::get, clock::get);

        // 1. Verify Bug A fix: a repeatable NPE inside doTask (after acquiring a pack) must NOT spin-retry
        //    or set poolStarved or drain the remaining buffer packs.
        {
            final int totalPacks = 4;
            final AtomicInteger freePacks = new AtomicInteger(totalPacks);
            final AtomicInteger attempts = new AtomicInteger();
            final AtomicInteger inFlight = new AtomicInteger();
            final AtomicBoolean poolStarved = new AtomicBoolean(false);
            final Queue<Integer> q = new ArrayDeque<>();
            q.add(1);

            int outcome = 0;
            inFlight.incrementAndGet();
            try {
                Integer item;
                synchronized (q) {
                    item = q.poll();
                }
                if (item != null) {
                    int prev = freePacks.getAndDecrement();
                    if (prev <= 0) {
                        freePacks.incrementAndGet();
                        outcome = 2; // STATE_POOL_EMPTY
                        synchronized (q) {
                            q.add(item);
                        }
                    } else {
                        outcome = 3; // STATE_PACK_ACQUIRED
                        attempts.incrementAndGet();
                        try {
                            throw new NullPointerException("modded mesher NPE");
                        } catch (NullPointerException npe) {
                            synchronized (q) {
                                q.add(item);
                            }
                        }
                    }
                }
            } finally {
                int remaining = inFlight.decrementAndGet();
                boolean redispatch = outcome != 3 && (outcome != 2 || (remaining == 0 && freePacks.get() > 0));
                if (!redispatch && outcome == 2 && remaining == 0) {
                    poolStarved.set(true);
                }
                require(!redispatch, "NPE inside doTask (pack held) must NOT trigger redispatch");
                require(!poolStarved.get(), "NPE inside doTask must NOT set poolStarved");
            }
            require(attempts.get() == 1, "must attempt exactly once, not spin");
            require(freePacks.get() == totalPacks - 1, "must not drain remaining buffer packs");
        }

        // 2. Normal multithreaded burst stress test with mid-flight limit changes (4 -> 3 -> 2 -> 4).
        final int totalTasks = 600;
        final Queue<Integer> queue = new ArrayDeque<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger activeWorkers = new AtomicInteger();
        final AtomicInteger peakActive = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger freeBuffers = new AtomicInteger(4);
        final CountDownLatch done = new CountDownLatch(totalTasks);
        final ExecutorService pool = Executors.newFixedThreadPool(9);

        final Runnable[] runner = new Runnable[1];
        final java.util.function.IntConsumer tryDispatch = maxNew -> {
            int limit = ChunkBuildPacer.maxConcurrentBuilds();
            int dispatched = 0;
            while (dispatched < maxNew) {
                int cur = inFlight.get();
                if (cur >= limit) {
                    return;
                }
                if (inFlight.compareAndSet(cur, cur + 1)) {
                    dispatched++;
                    pool.execute(runner[0]);
                }
            }
        };

        runner[0] = () -> {
            int outcome = 0; // PRE_ACQUIRE
            try {
                Integer item;
                synchronized (queue) {
                    item = queue.poll();
                }
                if (item == null) {
                    return;
                }
                int prevBuf = freeBuffers.getAndDecrement();
                if (prevBuf <= 0) {
                    freeBuffers.incrementAndGet();
                    outcome = 2; // POOL_EMPTY
                    synchronized (queue) {
                        queue.add(item);
                    }
                    return;
                }
                outcome = 3; // PACK_ACQUIRED
                try {
                    int nowActive = activeWorkers.incrementAndGet();
                    peakActive.accumulateAndGet(nowActive, Math::max);
                    long until = System.nanoTime() + 50_000L;
                    while (System.nanoTime() < until) {
                        Thread.onSpinWait();
                    }
                    activeWorkers.decrementAndGet();
                    completed.incrementAndGet();
                    done.countDown();
                } finally {
                    freeBuffers.incrementAndGet();
                }
                outcome = 1; // COMPLETED
            } finally {
                int remaining = inFlight.decrementAndGet();
                boolean shouldRedispatch = outcome == 1 || outcome == 0 || (outcome == 2 && remaining == 0 && freeBuffers.get() > 0);
                if (shouldRedispatch) {
                    int queued;
                    synchronized (queue) {
                        queued = queue.size();
                    }
                    if (queued > 0) {
                        tryDispatch.accept(queued);
                    }
                }
            }
        };

        try {
            for (int i = 0; i < totalTasks; i++) {
                if (i == 150) {
                    thermal.set(1);
                    clock.addAndGet(600_000_000L);
                } else if (i == 300) {
                    thermal.set(2);
                    clock.addAndGet(600_000_000L);
                } else if (i == 450) {
                    thermal.set(0);
                    clock.addAndGet(6_000_000_000L);
                }
                synchronized (queue) {
                    queue.add(i);
                }
                tryDispatch.accept(1);
                if (i % 50 == 49) {
                    Thread.sleep(2);
                }
            }
            require(done.await(15, TimeUnit.SECONDS), "timed out: completed=" + completed.get() + "/" + totalTasks);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (inFlight.get() != 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            require(completed.get() == totalTasks, "expected all tasks completed");
            require(inFlight.get() == 0, "expected inFlight=0, got " + inFlight.get());
            require(peakActive.get() <= 4, "peak concurrent builds exceeded max limit 4: " + peakActive.get());
        } finally {
            pool.shutdownNow();
        }
    }

    public static void main(final String[] args) throws Exception {
        testScaling();
        testBackendGateLoadingAndHysteresis();
        testDispatchProtocolAndNpeSafety();
        System.out.println("TestChunkBuildPacer OK");
    }
}
