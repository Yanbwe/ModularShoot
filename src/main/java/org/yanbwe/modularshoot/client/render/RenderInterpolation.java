package org.yanbwe.modularshoot.client.render;

import net.minecraft.world.phys.Vec3;

/**
 * Pure-function interpolation utilities for bullet rendering
 * (设计文档 §位置插值).
 *
 * <p>These helpers smooth the visual position of high-speed bullets across
 * render frames. The server syncs bullet positions once per tick; the client
 * renders many frames per tick. By linearly interpolating between the
 * previous-tick position and the current-tick position using the renderer's
 * {@code partialTick} (0 &rarr; 1 within a tick), the bullet appears to move
 * smoothly instead of snapping forward once per tick.</p>
 *
 * <p>All methods are pure: same inputs always produce the same output, with
 * no side effects. The class is not instantiable.</p>
 *
 * @see BulletRenderObject#getPrevPosition()
 * @see BulletRenderObject#getPosition()
 */
public final class RenderInterpolation {

    private RenderInterpolation() {
    }

    /**
     * Interpolates between two world positions using the renderer's partial
     * tick (设计文档 §位置插值).
     *
     * <p>At {@code partialTick = 0} the result is {@code prev}; at
     * {@code partialTick = 1} the result is {@code current}; values in
     * between produce a linear blend. This is the canonical way to smooth
     * bullet motion across frames between two server sync points.</p>
     *
     * @param prev        the previous-tick position
     * @param current     the current-tick position
     * @param partialTick the frame interpolation factor in {@code [0, 1]}
     * @return a new {@link Vec3} between {@code prev} and {@code current}
     */
    public static Vec3 lerpPosition(Vec3 prev, Vec3 current, float partialTick) {
        return prev.lerp(current, partialTick);
    }

    /**
     * Linearly interpolates between two scalar values.
     *
     * @param a the start value (returned when {@code t = 0})
     * @param b the end value (returned when {@code t = 1})
     * @param t the interpolation factor
     * @return {@code a + (b - a) * t}
     */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Linearly interpolates between two scalar values (double precision).
     *
     * @param a the start value (returned when {@code t = 0})
     * @param b the end value (returned when {@code t = 1})
     * @param t the interpolation factor
     * @return {@code a + (b - a) * t}
     */
    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
