package org.yanbwe.modularshoot.shooting;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Pure utility for applying elliptical spread to a look direction.
 *
 * <p>Implements the spread-sampling algorithm from 设计文档 §步骤六（精准度计算）.
 * The spread footprint is guaranteed to be an ellipse (a perfect circle when
 * {@code accuracyYaw == accuracyPitch}) rather than a rectangle: a radius
 * coefficient {@code r = sqrt(random)} is sampled so bullets cluster toward
 * the centre and thin out toward the edge, while an angle {@code θ} sampled
 * uniformly over {@code [0, 2π)} sweeps the full ellipse outline.
 *
 * <p>Attribute values {@code accuracy_yaw} / {@code accuracy_pitch} are stored
 * in <strong>degrees</strong> (range {@code [0, 360]}, see
 * {@link org.yanbwe.modularshoot.attribute.ModularShootAttributes}); they are
 * converted to radians internally before being handed to {@link Vec3#xRot}
 * / {@link Vec3#yRot}, which operate in radians.
 */
public final class SpreadCalculator {

    private SpreadCalculator() {
    }

    /**
     * Applies random elliptical spread to a look direction and returns the
     * deflected direction vector.
     *
     * <p>The deflection is applied as two sequential rotations on a copy of
     * the input vector: first a vertical deflection around the horizontal
     * (X) axis via {@link Vec3#xRot(float)}, then a horizontal deflection
     * around the vertical (Y) axis via {@link Vec3#yRot(float)}. Both
     * rotations consume radians; the degree-valued attributes are converted
     * with {@link Math#toRadians(double)}.
     *
     * <p>This is a pure function: it never mutates {@code lookAngle} and,
     * given the same inputs and the same {@link RandomSource} state, produces
     * the same output. When both accuracies are {@code 0} the original vector
     * is returned unchanged and no random values are consumed, so a perfectly
     * accurate shot preserves the aim exactly.
     *
     * @param lookAngle     the player's normalised look direction (e.g. from
     *                      {@code player.getLookAngle()}); never mutated
     * @param accuracyYaw   horizontal spread in degrees, range {@code [0, 360]}
     * @param accuracyPitch vertical spread in degrees, range {@code [0, 360]}
     * @param random        the server-side random source used to sample the
     *                      spread radius and angle
     * @return a new deflected direction vector; the original {@code lookAngle}
     *         when no spread is requested
     */
    public static Vec3 applySpread(Vec3 lookAngle, double accuracyYaw, double accuracyPitch, RandomSource random) {
        if (accuracyYaw == 0.0 && accuracyPitch == 0.0) {
            return lookAngle;
        }
        // r = sqrt(rand): centre-dense, edge-sparse distribution within the ellipse.
        double radius = Math.sqrt(random.nextDouble());
        // θ uniform over [0, 2π): sweeps the full ellipse outline.
        double theta = random.nextDouble() * 2.0 * Math.PI;
        // Deflection angles (degrees) — scaled by r so the footprint stays elliptical.
        double horizontalDeflectionDeg = radius * accuracyYaw * Math.cos(theta);
        double verticalDeflectionDeg = radius * accuracyPitch * Math.sin(theta);
        // Vec3 rotations operate in radians; convert from the degree-valued attributes.
        float verticalDeflectionRad = (float) Math.toRadians(verticalDeflectionDeg);
        float horizontalDeflectionRad = (float) Math.toRadians(horizontalDeflectionDeg);
        // Apply vertical deflection (around X axis) first, then horizontal (around Y axis).
        return lookAngle.xRot(verticalDeflectionRad).yRot(horizontalDeflectionRad);
    }
}
