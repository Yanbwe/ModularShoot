package org.yanbwe.modularshoot.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

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
 * <p><strong>回弹修复（time-based interpolation）。</strong> The original
 * scheme advanced the interpolation pair (prevPosition → position) on packet
 * arrival — the server-tick clock — while the render factor came from the
 * client's {@code partialTick}, which resets at every <em>client</em> tick.
 * The two clocks drift, so a client tick that processed zero packets replayed
 * the stale pair from {@code partialTick = 0}: the bullet visually bounced
 * backward one server step. {@link #interpolationFactor} drives the factor
 * from the wall-clock time since the pair was last advanced, so the factor
 * only ever grows toward 1 and never resets while the pair is stale — the
 * bullet holds at its current position instead of bouncing back.</p>
 *
 * <p>All methods are pure: same inputs always produce the same output, with
 * no side effects. The class is not instantiable.</p>
 *
 * @see BulletRenderObject#getPrevPosition()
 * @see BulletRenderObject#getPosition()
 * @see BulletRenderObject#getInterpolationFactor(long)
 */
public final class RenderInterpolation {

    private RenderInterpolation() {
    }

    /**
     * Computes the time-based interpolation factor for a position segment
     * (回弹修复).
     *
     * <p>The segment spans {@code lastUpdateMillis} (when the interpolation
     * pair was last advanced by a sync packet) to
     * {@code lastUpdateMillis + expectedSpanMillis}. The factor grows linearly
     * from 0 at the update moment to 1 at the end of the expected span, then
     * <em>saturates at 1</em> — it never resets while the pair is stale, which
     * is the anti-bounce property (a stale pair holding at {@code current}
     * beats bouncing back to {@code prev}).</p>
     *
     * <p>Guards: a non-positive expected span (instant double packet) is
     * treated as a completed segment; {@code nowMillis} before
     * {@code lastUpdateMillis} (wall-clock adjustment) degrades to the segment
     * start so the lerp never runs backward.</p>
     *
     * @param nowMillis          the current wall-clock time in millis
     * @param lastUpdateMillis   when the interpolation pair was last advanced
     * @param expectedSpanMillis how long this segment is expected to last
     *                           (the previously measured elapsed between
     *                           updates; 50ms at a healthy 20 TPS)
     * @return the interpolation factor in {@code [0, 1]}
     */
    public static float interpolationFactor(
            long nowMillis, long lastUpdateMillis, long expectedSpanMillis) {
        if (expectedSpanMillis <= 0L) {
            return 1.0F;
        }
        if (nowMillis <= lastUpdateMillis) {
            return 0.0F;
        }
        long elapsed = nowMillis - lastUpdateMillis;
        return Math.min(1.0F, (float) elapsed / (float) expectedSpanMillis);
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
     * Write-into variant of {@link #lerpPosition(Vec3, Vec3, float)}: computes
     * the same linear blend but writes the three components into the caller's
     * reusable {@code out} (a JOML {@link Vector3d}) and returns it, avoiding
     * the per-frame per-bullet {@link Vec3} allocation of {@code Vec3.lerp}
     * (审查优化: 渲染热路径对象复用).
     *
     * <p>The render thread is single-threaded and the result is consumed
     * synchronously, so the caller may keep one reusable {@code out} and pass
     * it on every frame.</p>
     *
     * @param prev        the previous-tick position
     * @param current     the current-tick position
     * @param partialTick the frame interpolation factor in {@code [0, 1]}
     * @param out         the reusable output vector to write into; never
     *                    {@code null}
     * @return {@code out} with its {@code x}/{@code y}/{@code z} set to the
     *         blended result
     */
    public static Vector3d lerpPosition(
            Vec3 prev, Vec3 current, float partialTick, Vector3d out) {
        return out.set(
                lerp(prev.x, current.x, partialTick),
                lerp(prev.y, current.y, partialTick),
                lerp(prev.z, current.z, partialTick));
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
