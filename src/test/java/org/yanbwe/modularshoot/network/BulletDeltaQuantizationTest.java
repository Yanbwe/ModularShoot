package org.yanbwe.modularshoot.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link BulletDeltaQuantizer} —the pure fixed-point
 * quantization / threshold / distance-decimation rules behind the bullet
 * incremental-sync bandwidth reduction (阶段 2 / 任务 2.2).
 *
 * <p>Covered behaviours:</p>
 * <ul>
 *   <li>a sub-quantum (sub-pixel) displacement no longer triggers a delta
 *       send, so bullets do not spam a packet every tick;</li>
 *   <li>a displacement that crosses a {@code 1/128} block quantization cell
 *       boundary <em>does</em> trigger a delta send;</li>
 *   <li>direction changes are quantized the same way;</li>
 *   <li>distant bullets are decimated to a lower update frequency (a minimum
 *       tick interval per distance band), while near bullets stay eligible
 *       every tick.</li>
 * </ul>
 *
 * <p>The helper is deliberately a stateless, pure function of
 * (current, last-sent, distance, tick) so the server keeps full precision for
 * later comparison while the <em>decision</em> is made on the quantized grid
 * and the distance band.</p>
 */
class BulletDeltaQuantizationTest {

    // ---- Fixed-point position quantization ----------------------------

    @Test
    void subQuantumDisplacementDoesNotTriggerDelta() {
        // A movement smaller than one 1/128-block cell must not send a delta,
        // i.e. the bullet is still on the same quantization cell.
        double lastX = 10.0;
        double lastY = 64.0;
        double lastZ = -20.0;
        double curX = lastX + 0.003; // well under a 1/128 cell (0.0078125)
        double curY = lastY - 0.002;
        double curZ = lastZ + 0.001;

        assertFalse(BulletDeltaQuantizer.positionChanged(curX, curY, curZ, lastX, lastY, lastZ),
                "sub-quantum displacement must not be considered a position change");
    }

    @Test
    void subQuantumDisplacementOnSingleAxisDoesNotTriggerDelta() {
        double lastX = 5.0;
        // +0.002 on x only; inside half a cell (0.00390625) of 5.0, so it
        // rounds back to the same cell and must not count as a change.
        assertFalse(BulletDeltaQuantizer.positionChanged(5.002, 0, 0, lastX, 0, 0));
    }

    @Test
    void quantumBoundaryCrossingTriggersDelta() {
        // Moving from one 1/128 cell to the next must send a delta.
        double lastX = 10.0;
        double curX = lastX + 0.008; // > one cell (0.0078125)

        assertTrue(BulletDeltaQuantizer.positionChanged(curX, 64, -20, lastX, 64, -20),
                "crossing a quantization cell boundary must trigger a delta");
    }

    @Test
    void accumulatedSubQuantumMovementEventuallyCrossesBoundary() {
        // A bullet creeping at 0.003 blocks/tick crosses a cell boundary
        // after enough ticks: the accumulated position differs by a full cell.
        double lastX = 10.0;
        double curX = lastX + 0.003 * 4; // 0.012 > one cell

        assertTrue(BulletDeltaQuantizer.positionChanged(curX, 0, 0, lastX, 0, 0),
                "accumulated sub-quantum steps must eventually register as a change");
    }

    @Test
    void unchangedPositionNeverSends() {
        double a = 1.5, b = 2.5, c = 3.5;
        assertFalse(BulletDeltaQuantizer.positionChanged(a, b, c, a, b, c),
                "identical position must never count as a change");
    }

    @Test
    void quantizePositionRoundsToNearestCell() {
        // 10.003 is closer to 10.0 than to 10.0078125 -> rounds back to 10.0.
        assertEquals(10.0, BulletDeltaQuantizer.quantizePosition(10.003), 1.0e-9);
        // 10.006 is closer to 10.0078125 -> rounds up a cell.
        assertEquals(10.0 + 1.0 / 128.0,
                BulletDeltaQuantizer.quantizePosition(10.006), 1.0e-9);
    }

    // ---- Direction quantization ---------------------------------------

    @Test
    void directionChangeBeyondQuantumTriggersDelta() {
        double lastDx = 0.0, lastDy = 1.0, lastDz = 0.0;
        double curDx = 0.01, curDy = 1.0, curDz = 0.0; // > a 1/128 cell

        assertTrue(BulletDeltaQuantizer.directionChanged(curDx, curDy, curDz, lastDx, lastDy, lastDz),
                "a direction change larger than the quantum must trigger a delta");
    }

    @Test
    void directionChangeWithinQuantumDoesNotTriggerDelta() {
        double lastDx = 0.0, lastDy = 1.0, lastDz = 0.0;
        double curDx = 0.003, curDy = 1.0, curDz = 0.0; // within a 1/128 cell

        assertFalse(BulletDeltaQuantizer.directionChanged(curDx, curDy, curDz, lastDx, lastDy, lastDz),
                "a sub-quantum direction change must not trigger a delta");
    }

    @Test
    void unchangedDirectionNeverSends() {
        double a = 0.0, b = 1.0, c = 0.0;
        assertFalse(BulletDeltaQuantizer.directionChanged(a, b, c, a, b, c));
    }

    // ---- Distance-based update-frequency decimation -------------------

    @Test
    void nearBulletIsEligibleEveryTick() {
        double closeDistance = 16.0; // well within the close band

        assertEquals(1, BulletDeltaQuantizer.updateIntervalTicks(closeDistance),
                "close bullets may update every tick");
        assertTrue(BulletDeltaQuantizer.isUpdateEligible(closeDistance, 100, 99),
                "a close bullet may update even one tick after the last send");
    }

    @Test
    void midDistanceBulletIsThrottledToEveryOtherTick() {
        double midDistance = 48.0; // in the mid band (32 < d <= 64)

        assertEquals(2, BulletDeltaQuantizer.updateIntervalTicks(midDistance),
                "mid-distance bullets update at most every 2 ticks");
        assertFalse(BulletDeltaQuantizer.isUpdateEligible(midDistance, 100, 99),
                "a mid bullet updated last tick must not send again immediately");
        assertTrue(BulletDeltaQuantizer.isUpdateEligible(midDistance, 101, 99),
                "a mid bullet may update once two ticks have elapsed");
    }

    @Test
    void farBulletIsThrottledToEveryFourthTick() {
        double farDistance = 100.0; // beyond the mid band

        assertEquals(4, BulletDeltaQuantizer.updateIntervalTicks(farDistance),
                "far bullets update at most every 4 ticks");
        assertFalse(BulletDeltaQuantizer.isUpdateEligible(farDistance, 102, 99),
                "a far bullet updated three ticks ago must still wait");
        assertTrue(BulletDeltaQuantizer.isUpdateEligible(farDistance, 103, 99),
                "a far bullet may update once four ticks have elapsed");
    }

    @Test
    void midBandBoundaryDistanceIsInclusiveAtUpperEdge() {
        // At exactly the mid radius the bullet still uses the mid interval (2).
        assertEquals(2, BulletDeltaQuantizer.updateIntervalTicks(64.0),
                "the mid band upper bound is inclusive");
    }

    @Test
    void nearBandLowerBoundaryReturnsOne() {
        // At exactly the close radius the near band is still in effect
        // (distance <= CLOSE_DISTANCE -> eligible every tick).
        assertEquals(1, BulletDeltaQuantizer.updateIntervalTicks(32.0),
                "the close band lower/upper bound must still allow every-tick updates");
        assertTrue(BulletDeltaQuantizer.isUpdateEligible(32.0, 100, 99),
                "a bullet exactly at the close radius may update one tick after the last send");
    }

    @Test
    void farBandStartsJustBeyondMidRadius() {
        // Just past the mid radius (64.0 + epsilon) the far band takes over,
        // throttling to every 4 ticks. The mid interval must NOT be used there.
        assertEquals(4, BulletDeltaQuantizer.updateIntervalTicks(64.0 + 1.0e-9),
                "the far band lower bound starts strictly above the mid radius");
        assertFalse(BulletDeltaQuantizer.isUpdateEligible(64.0 + 1.0e-9, 102, 99),
                "a far-band bullet updated three ticks ago must still wait");
        assertTrue(BulletDeltaQuantizer.isUpdateEligible(64.0 + 1.0e-9, 103, 99),
                "a far-band bullet may update once four ticks have elapsed");
    }

    // ---- Negative coordinates ------------------------------------------

    @Test
    void negativeCoordinatesQuantizeSymmetrically() {
        // Negative world coordinates must round to the same fixed-point cell
        // grid as their positive mirror, with no sign asymmetry.
        double qPos = BulletDeltaQuantizer.quantizePosition(10.003);
        double qNeg = BulletDeltaQuantizer.quantizePosition(-10.003);
        // Math.round(-10.003 / (1/128)) * (1/128) = -10.0
        assertEquals(-10.0, qNeg, 1.0e-9);
        // The negative mirror of a positive value must equal the negation of
        // the positive quantized value (sign symmetry).
        assertEquals(-qPos, qNeg, 1.0e-9,
                "quantization must be sign-symmetric for negative coordinates");
    }

    @Test
    void negativeCoordinateBoundaryCrossingTriggersDelta() {
        // A displacement that crosses a 1/128 cell on the negative axis must
        // be detected just as on the positive axis.
        double lastX = -10.0;
        double curX = lastX - 0.008; // > one cell (0.0078125) further negative

        assertTrue(BulletDeltaQuantizer.positionChanged(curX, -64, -20, lastX, -64, -20),
                "crossing a quantization cell boundary on negative coordinates must trigger a delta");
    }

    @Test
    void negativeCoordinateSubQuantumChangeDoesNotTriggerDelta() {
        double lastX = -10.0;
        double curX = lastX - 0.003; // well under a half cell (0.00390625)

        assertFalse(BulletDeltaQuantizer.positionChanged(curX, -64, -20, lastX, -64, -20),
                "a sub-quantum displacement on negative coordinates must not trigger a delta");
    }
}
