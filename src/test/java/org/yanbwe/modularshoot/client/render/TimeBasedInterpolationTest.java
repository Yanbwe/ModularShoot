package org.yanbwe.modularshoot.client.render;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the time-based bullet interpolation fix (回弹修复).
 *
 * <p>The previous interpolation pair (prevPosition → position) was advanced by
 * network packets (server-tick clock) while the render factor came from the
 * client's {@code partialTick} (client-tick clock). The two clocks drift, so a
 * client tick that processed zero packets replayed the stale pair from
 * {@code partialTick = 0} — the bullet visually bounced backward one server
 * step. The fix drives the interpolation factor from the wall-clock time since
 * the pair was last advanced, so the factor only ever grows toward 1 and never
 * resets while the pair is stale.</p>
 *
 * <p>The factor function {@link RenderInterpolation#interpolationFactor} is a
 * pure function of (now, lastUpdate, span); the render object keeps the
 * segment bookkeeping ({@link BulletRenderObject#getInterpolationFactor(long)})
 * deterministic via the sentinel {@code lastUpdateMillis = 0} ("no segment
 * started yet") and an explicit-time {@code updatePosition} overload.</p>
 */
class TimeBasedInterpolationTest {

    private static final float EPSILON = 1e-5F;

    // --- RenderInterpolation.interpolationFactor (pure function) ----------

    @Test
    void factorIsZeroAtSegmentStart() {
        assertEquals(0.0F, RenderInterpolation.interpolationFactor(1000L, 1000L, 50L), EPSILON,
                "at the update moment the segment has not progressed");
    }

    @Test
    void factorIsOneExactlyAtSpanEnd() {
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(1050L, 1000L, 50L), EPSILON,
                "exactly one span after the update the segment is complete");
    }

    @Test
    void factorScalesLinearlyWithinTheSpan() {
        assertEquals(0.5F, RenderInterpolation.interpolationFactor(1025L, 1000L, 50L), EPSILON,
                "halfway through the span");
        assertEquals(0.24F, RenderInterpolation.interpolationFactor(1012L, 1000L, 50L), EPSILON,
                "12ms into the 50ms span → 0.24");
    }

    @Test
    void factorClampsAtOneBeyondTheSpan() {
        // The anti-bounce property: while the pair is stale (no packet arrived),
        // the factor must NOT reset — it saturates at 1 so the bullet holds at
        // the current position instead of jumping back toward prevPosition.
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(2000L, 1000L, 50L), EPSILON,
                "long after the span the factor stays at 1");
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(1075L, 1000L, 50L), EPSILON,
                "just past the span the factor is already clamped");
    }

    @Test
    void factorNeverDecreasesWithTime() {
        // Monotonicity: for any later now, the factor is >= the previous value.
        float previous = 0.0F;
        for (long now = 1000L; now <= 1400L; now += 7L) {
            float factor = RenderInterpolation.interpolationFactor(now, 1000L, 50L);
            assertTrue(factor >= previous,
                    "factor must never decrease as time advances (at " + now + "ms: " + factor + " < " + previous + ")");
            previous = factor;
        }
    }

    @Test
    void factorHandlesClockGoingBackward() {
        // Defensive: a wall-clock adjustment must never produce a negative
        // factor (which would lerp the bullet backward).
        assertEquals(0.0F, RenderInterpolation.interpolationFactor(900L, 1000L, 50L), EPSILON,
                "now before the update moment degrades to the segment start");
    }

    @Test
    void factorHandlesNonPositiveSpan() {
        // Defensive: a zero/negative expected span (instant double packet) must
        // not divide by zero or overflow — the segment is treated as complete.
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(1000L, 1000L, 0L), EPSILON,
                "zero span");
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(1000L, 1000L, -10L), EPSILON,
                "negative span");
    }

    @Test
    void factorAdaptsToDifferentSpanLengths() {
        // A slower server (longer real span) must still reach 1 exactly at its
        // own span end — the bullet crawls to catch up instead of snapping.
        assertEquals(0.5F, RenderInterpolation.interpolationFactor(1050L, 1000L, 100L), EPSILON,
                "midpoint of a 100ms span");
        assertEquals(1.0F, RenderInterpolation.interpolationFactor(1100L, 1000L, 100L), EPSILON,
                "end of a 100ms span");
    }

    // --- BulletRenderObject segment bookkeeping ---------------------------

    @Test
    void freshObjectHasNoSegment() {
        BulletRenderObject obj = new BulletRenderObject(
                1, new Vec3(0, 0, 0), new Vec3(1, 0, 0),
                null, null, BulletRenderObject.RENDER_MODE_BILLBOARD,
                1.0F, null, java.util.List.of());
        // No updatePosition yet: the sentinel "no segment" state holds the
        // bullet at its initial position regardless of elapsed time.
        assertEquals(0.0F, obj.getInterpolationFactor(123456789L), EPSILON,
                "before the first update the factor stays 0");
    }

    @Test
    void firstUpdateUsesDefaultSpan() {
        BulletRenderObject obj = new BulletRenderObject(
                1, new Vec3(0, 0, 0), new Vec3(1, 0, 0),
                null, null, BulletRenderObject.RENDER_MODE_BILLBOARD,
                1.0F, null, java.util.List.of());
        // First segment: no previous segment to measure, so the default 50ms
        // span applies (one server tick at 20 TPS).
        obj.updatePosition(new Vec3(1, 0, 0), 1000L);
        assertEquals(0.0F, obj.getInterpolationFactor(1000L), EPSILON, "segment start");
        assertEquals(1.0F, obj.getInterpolationFactor(1050L), EPSILON, "default span end");
    }

    @Test
    void subsequentSegmentsAdaptToMeasuredElapsed() {
        BulletRenderObject obj = new BulletRenderObject(
                1, new Vec3(0, 0, 0), new Vec3(1, 0, 0),
                null, null, BulletRenderObject.RENDER_MODE_BILLBOARD,
                1.0F, null, java.util.List.of());
        // First segment: default span.
        obj.updatePosition(new Vec3(1, 0, 0), 1000L);
        // Second segment arrives 100ms later → the next segment is expected to
        // last 100ms (adaptive: slow server → slow crawl, no snap, no bounce).
        obj.updatePosition(new Vec3(2, 0, 0), 1100L);
        assertEquals(0.0F, obj.getInterpolationFactor(1100L), EPSILON, "new segment start");
        assertEquals(0.5F, obj.getInterpolationFactor(1150L), EPSILON, "midpoint of the adapted 100ms span");
        assertEquals(1.0F, obj.getInterpolationFactor(1200L), EPSILON, "end of the adapted 100ms span");
        // And it must never bounce back even when no further packet arrives.
        assertEquals(1.0F, obj.getInterpolationFactor(5000L), EPSILON,
                "stale pair holds at the current position (anti-bounce)");
    }

    @Test
    void instantDoubleUpdateKeepsPreviousSpan() {
        BulletRenderObject obj = new BulletRenderObject(
                1, new Vec3(0, 0, 0), new Vec3(1, 0, 0),
                null, null, BulletRenderObject.RENDER_MODE_BILLBOARD,
                1.0F, null, java.util.List.of());
        // Two packets processed in the same network drain (elapsed 0): the span
        // must not collapse to 0 — the previous span is preserved so the second
        // segment renders at normal speed instead of teleporting.
        obj.updatePosition(new Vec3(1, 0, 0), 1000L);
        obj.updatePosition(new Vec3(2, 0, 0), 1000L);
        assertEquals(1.0F, obj.getInterpolationFactor(1050L), EPSILON,
                "second segment keeps the default 50ms span");
        assertEquals(0.5F, obj.getInterpolationFactor(1025L), EPSILON,
                "second segment midpoint uses the preserved span");
    }
}
