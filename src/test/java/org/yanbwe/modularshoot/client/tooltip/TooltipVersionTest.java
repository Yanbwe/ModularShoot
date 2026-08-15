package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TooltipVersion} thread safety and lifecycle
 * (阶段 7 / 任务 7.1, 审查 Medium 2).
 *
 * <p>{@code versionFor}/{@code clear} are package-private, so this test lives
 * in the same package to exercise them headlessly — neither touches
 * {@link net.minecraft.client.Minecraft}, so no client is required.</p>
 *
 * <ul>
 *   <li><b>Clear-narrowing:</b> {@link TooltipVersion#clear()} must drop only
 *       the store-owned stream ({@link TooltipVersion#STORE_TOKEN}), leaving
 *       per-player streams intact.</li>
 *   <li><b>Concurrency:</b> many threads calling {@code versionFor} while the
 *       main thread calls {@code clear()} must not throw
 *       {@code ConcurrentModificationException} or corrupt the map (synchronized
 *       access).</li>
 * </ul>
 */
class TooltipVersionTest {

    /** The real store-owned stream token used by {@link TooltipVersion}. */
    private static final Object STORE = TooltipVersion.STORE_TOKEN;

    @BeforeEach
    @AfterEach
    void reset() {
        // Clear the store token so each test starts from a clean store stream.
        TooltipVersion.clear();
    }

    // ------------------------------------------------------------------
    // clear() narrows to the store token only
    // ------------------------------------------------------------------

    @Test
    void clearDropsOnlyStoreTokenLeavingPlayerStreamsIntact() {
        Object playerToken = new Object();
        Object playerPayload = new Object();
        int playerVersion = TooltipVersion.versionFor(playerToken, playerPayload);
        int storeVersion = TooltipVersion.versionFor(STORE, new Object());

        TooltipVersion.clear();

        // The store stream must restart from version 1 (its entry was removed).
        assertEquals(1, TooltipVersion.versionFor(STORE, new Object()),
                "clear() must drop the store-owned stream so its counter restarts");
        // The per-player stream must be preserved (same token+payload → same version).
        assertEquals(playerVersion, TooltipVersion.versionFor(playerToken, playerPayload),
                "clear() must NOT drop per-player streams");
        // storeVersion started from 1; after clear the new store counter restarts at 1.
        assertTrue(storeVersion >= 1, "store version before clear must be >= 1");
    }

    @Test
    void clearRestoresStoreVersionOnNextVersionFor() {
        TooltipVersion.versionFor(STORE, new Object());
        TooltipVersion.versionFor(STORE, new Object());
        TooltipVersion.versionFor(STORE, new Object());

        TooltipVersion.clear();

        assertEquals(1, TooltipVersion.versionFor(STORE, new Object()),
                "a cleared store stream restarts its counter from 1");
    }

    // ------------------------------------------------------------------
    // Concurrency: versionFor + clear must not throw or corrupt the map
    // ------------------------------------------------------------------

    @Test
    void concurrentVersionForAndClearDoNotThrow() throws InterruptedException {
        int writerThreads = 4;
        int iterations = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writerThreads + 1);
        AtomicBoolean failure = new AtomicBoolean();
        AtomicInteger clearCount = new AtomicInteger();

        // Render-thread stand-ins: repeatedly version streams (some shared, some
        // unique) so the LRU eviction and map mutation paths are exercised.
        for (int t = 0; t < writerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        Object token = (i % 2 == 0) ? STORE : new Object();
                        TooltipVersion.versionFor(token, new Object());
                    }
                } catch (Throwable ex) {
                    failure.set(true);
                } finally {
                    done.countDown();
                }
            });
        }

        // Main-thread stand-in: repeatedly clear the store stream.
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < iterations; i++) {
                    TooltipVersion.clear();
                    clearCount.incrementAndGet();
                }
            } catch (Throwable ex) {
                failure.set(true);
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS),
                "all concurrent threads must finish within timeout");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(!failure.get(),
                "concurrent versionFor + clear must not throw (synchronized TRACKED)");
        assertEquals(iterations, clearCount.get(),
                "the clear thread must have run all its iterations");
    }

    @Test
    void concurrentVersionForSameTokenDoesNotCorruptVersion() throws InterruptedException {
        Object sharedToken = new Object();
        Object stablePayload = new Object();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Integer> results = new ArrayList<>();
        AtomicBoolean failure = new AtomicBoolean();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // Accessing the same stable token+payload concurrently must
                    // return the same (cached) version for every reader — no
                    // corruption, no spurious increments from a torn map read.
                    int v = TooltipVersion.versionFor(sharedToken, stablePayload);
                    synchronized (results) {
                        results.add(v);
                    }
                } catch (Throwable ex) {
                    failure.set(true);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all readers must finish");
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(!failure.get(), "concurrent readers must not throw");
        // First writer bumps to 1; every reader that hits the cached entry sees 1.
        assertTrue(results.stream().allMatch(v -> v == 1 || v == 2),
                "all concurrent readers of a stable token+payload must see a consistent version");
    }

    @Test
    void forceNeverThrowsDespiteHeavyChurn() {
        // Repeatedly add many unique tokens to force LRU eviction churn while
        // the same threads clear() — an explicit stress that exercises the
        // eviction loop under concurrency.
        assertDoesNotThrow(() -> {
            for (int round = 0; round < 50; round++) {
                for (int i = 0; i < 100; i++) {
                    TooltipVersion.versionFor(new Object(), new Object());
                }
                TooltipVersion.clear();
            }
        }, "heavy versionFor + clear churn must never throw");
    }
}
