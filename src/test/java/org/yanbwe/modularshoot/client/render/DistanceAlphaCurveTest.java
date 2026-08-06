package org.yanbwe.modularshoot.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DistanceAlphaCurve#computeAlpha}: the near-camera
 * distance→opacity curve used to fade bullets that fly close to the camera.
 * The curve is a pure function of (distanceSqr, farDistance, minAlpha) — no
 * Minecraft state — so it is exercised with plain JUnit.
 */
class DistanceAlphaCurveTest {

    private static final double FAR = 2.0;
    private static final float MIN = 0.2F;

    private static final float EPSILON = 1e-6F;

    /** Distance in blocks → the squared distance the API receives. */
    private static double sq(double distance) {
        return distance * distance;
    }

    @Test
    void zeroDistanceYieldsMinAlpha() {
        assertEquals(MIN, DistanceAlphaCurve.computeAlpha(sq(0.0), FAR, MIN), EPSILON,
                "a bullet at the camera's position is drawn at minimum opacity");
    }

    @Test
    void beyondFarDistanceIsFullyOpaque() {
        assertEquals(1.0F, DistanceAlphaCurve.computeAlpha(sq(3.0), FAR, MIN), EPSILON,
                "well beyond the fade distance");
        assertEquals(1.0F, DistanceAlphaCurve.computeAlpha(sq(FAR), FAR, MIN), EPSILON,
                "exactly at the fade distance");
    }

    @Test
    void halfFadeDistanceIsTheSmoothstepMidpoint() {
        // t = 1/2 → smoothstep(0.5) = 0.5 → alpha = 0.2 + 0.8 × 0.5 = 0.6.
        assertEquals(0.6F, DistanceAlphaCurve.computeAlpha(sq(1.0), FAR, MIN), EPSILON,
                "the midpoint of the curve is the linear midpoint because smoothstep(0.5) = 0.5");
    }

    @Test
    void minAlphaScalesTheWholeRamp() {
        // minAlpha = 0.5: at distance 0 → 0.5; at the midpoint → 0.5 + 0.5 × 0.5 = 0.75.
        float min = 0.5F;
        assertEquals(min, DistanceAlphaCurve.computeAlpha(sq(0.0), FAR, min), EPSILON,
                "at distance 0 the result is the configured minimum");
        assertEquals(0.75F, DistanceAlphaCurve.computeAlpha(sq(1.0), FAR, min), EPSILON,
                "midpoint scales with the configured minimum");
    }

    @Test
    void farDistanceScalesTheRamp() {
        // far = 4: distance 2 is the midpoint → 0.6; distance 4 is fully opaque.
        assertEquals(0.6F, DistanceAlphaCurve.computeAlpha(sq(2.0), 4.0, MIN), EPSILON,
                "the midpoint follows the configured fade distance");
        assertEquals(1.0F, DistanceAlphaCurve.computeAlpha(sq(4.0), 4.0, MIN), EPSILON,
                "fully opaque exactly at the configured fade distance");
    }

    @Test
    void minAlphaOfOneKeepsEverythingOpaque() {
        // minOpacity = 1.0 is the config's "disable the fade" value.
        assertEquals(1.0F, DistanceAlphaCurve.computeAlpha(sq(0.0), FAR, 1.0F), EPSILON,
                "minimum opacity 1.0 disables the fade entirely");
    }

    @Test
    void acceptsSquaredDistanceInput() {
        // The caller supplies distanceToSqr; 2.5² with far 5 → t = 0.5 → 0.6.
        assertEquals(0.6F, DistanceAlphaCurve.computeAlpha(2.5 * 2.5, 5.0, MIN), EPSILON,
                "the squared distance is converted back before the t computation");
    }

    @Test
    void alphaIsMonotonicNonDecreasingAcrossTheRamp() {
        float previous = 0.0F;
        for (double d = 0.0; d <= FAR + 0.5; d += 0.1) {
            float alpha = DistanceAlphaCurve.computeAlpha(sq(d), FAR, MIN);
            assertTrue(alpha >= previous,
                    "alpha must never decrease as distance grows (at " + d + " blocks: " + alpha + " < " + previous + ")");
            previous = alpha;
        }
        assertEquals(1.0F, previous, EPSILON, "the ramp ends fully opaque");
    }
}
