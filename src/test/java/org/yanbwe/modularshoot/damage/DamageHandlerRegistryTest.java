package org.yanbwe.modularshoot.damage;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.ComposedBulletStyle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link DamageHandlerRegistry} cleanups
 * (阶段 7 / 任务 7.1 — 审查 Medium 1).
 *
 * <p>{@code processChain} needs a real {@link Entity}, which is heavy to
 * bootstrap headlessly, so the tests target the extracted minimal seams
 * (阶段 7 / 任务 7.1 — 最小可测 seam):</p>
 * <ul>
 *   <li><b>Empty chain:</b> {@link DamageHandlerRegistry#runChain} with an
 *       empty list returns {@code baseDamage} unchanged (mirrors the empty
 *       registry short-circuit).</li>
 *   <li><b>Exception isolation + chain continue:</b> a throwing handler is
 *       logged once per minute bucket and skipped; the next handler still runs
 *       on the last successfully computed value.</li>
 *   <li><b>Minute-bucket log throttle:</b> {@link DamageHandlerRegistry#shouldLog}
 *       emits at most once per whole-minute bucket per handler class.</li>
 * </ul>
 */
class DamageHandlerRegistryTest {

    private static BulletRecord bullet() {
        return new BulletRecord(
                new BulletSnapshot(
                        new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>()),
                null, Vec3.ZERO, new Vec3(1, 0, 0), 1, ComposedBulletStyle.DEFAULT);
    }

    @BeforeEach
    @AfterEach
    void resetTimeBuckets() {
        // The static rate-limit map is shared; reset between cases so the
        // minute-bucket assertions are deterministic.
        DamageHandlerRegistry.resetRateLimitState();
    }

    // ------------------------------------------------------------------
    // Empty chain returns baseDamage unchanged
    // ------------------------------------------------------------------

    @Test
    void emptyChainReturnsBaseDamageUnchanged() {
        assertEquals(42.0, DamageHandlerRegistry.runChain(List.of(), bullet(), null, 42.0),
                "an empty handler list returns the base damage unmodified");
    }

    // ------------------------------------------------------------------
    // Exception isolation: throwing handler skipped, chain continues
    // ------------------------------------------------------------------

    @Test
    void throwingHandlerIsSkippedAndChainContinues() {
        DamageHandler throwing = (b, t, d) -> {
            throw new IllegalStateException("deliberate test failure");
        };
        DamageHandler passThrough = (b, t, d) -> d * 2.0;

        double result = DamageHandlerRegistry.runChain(
                List.of(throwing, passThrough), bullet(), null, 10.0);

        // The throwing handler is logged and skipped; the chain continues from
        // the last successfully computed value (10.0) through the second handler.
        assertEquals(20.0, result,
                "a throwing handler is skipped and the remaining chain continues");
    }

    @Test
    void throwingHandlerDoesNotAbortLaterHandlers() {
        AtomicInteger laterInvocations = new AtomicInteger();
        DamageHandler throwing = (b, t, d) -> {
            throw new RuntimeException("boom");
        };
        DamageHandler later = (b, t, d) -> {
            laterInvocations.incrementAndGet();
            return d + 1;
        };

        double result = DamageHandlerRegistry.runChain(
                List.of(throwing, later), bullet(), null, 5.0);

        assertEquals(6.0, result, "the later handler still runs after an exception");
        assertEquals(1, laterInvocations.get(), "the later handler is invoked exactly once");
    }

    @Test
    void exceptionIsSwallowedNeverEscapesChain() {
        DamageHandler throwing = (b, t, d) -> {
            throw new RuntimeException("must be swallowed");
        };
        // No later handlers: the exception must still be swallowed (no throw).
        assertEquals(7.0, DamageHandlerRegistry.runChain(
                        List.of(throwing), bullet(), null, 7.0),
                "a throwing handler's exception is swallowed and the last value is returned");
    }

    // ------------------------------------------------------------------
    // Minute-bucket log throttle (whole-minute bucket semantics)
    // ------------------------------------------------------------------

    @Test
    void sameMinuteBucketSuppressesSecondLogForSameHandler() {
        String key = "examplemod.ThrowingHandler";

        assertTrue(DamageHandlerRegistry.shouldLog(key, 100L),
                "first occurrence in a bucket logs");
        assertFalse(DamageHandlerRegistry.shouldLog(key, 100L),
                "second occurrence in the same bucket is suppressed");
    }

    @Test
    void newMinuteBucketAllowsAnotherLog() {
        String key = "examplemod.OtherHandler";

        assertTrue(DamageHandlerRegistry.shouldLog(key, 100L),
                "first bucket logs");
        assertFalse(DamageHandlerRegistry.shouldLog(key, 100L),
                "same bucket suppressed");
        // A later minute bucket (whole-minute bucket semantics) logs again.
        assertTrue(DamageHandlerRegistry.shouldLog(key, 101L),
                "a new minute bucket permits another log");
    }

    @Test
    void differentHandlersHaveIndependentBuckets() {
        // Independent per-class buckets: two distinct handlers never suppress
        // each other even within the same minute bucket.
        assertTrue(DamageHandlerRegistry.shouldLog("mod.h1", 200L));
        assertTrue(DamageHandlerRegistry.shouldLog("mod.h2", 200L));
        assertFalse(DamageHandlerRegistry.shouldLog("mod.h1", 200L),
                "h1's second hit in the same bucket is suppressed");
        assertFalse(DamageHandlerRegistry.shouldLog("mod.h2", 200L),
                "h2's second hit in the same bucket is suppressed");
    }
}
