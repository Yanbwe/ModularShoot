package org.yanbwe.modularshoot.client.render;

/**
 * Pure distance→opacity curve for the near-camera bullet translucency
 * feature (系统七 §近相机距离透明度).
 *
 * <p>Bullets flying close to the camera are drawn semi-transparent so they
 * do not occlude the player's view. The opacity ramps from a configurable
 * minimum at distance {@code 0} to fully opaque at the configured fade
 * distance, following a {@code smoothstep} curve (design choice: the
 * derivative is zero at both ends, so the fade starts and ends gently
 * instead of switching abruptly).</p>
 *
 * <p>The function is a pure mathematical mapping — no Minecraft state — so
 * it is unit-tested directly and may be reused anywhere the caller has the
 * camera and bullet positions (design document §渲染流程). The caller
 * supplies the <em>squared</em> distance (the form {@code Vec3.distanceToSqr}
 * returns) to avoid an extra square root where the caller already holds it;
 * the curve itself still operates on the linear distance.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see BulletRenderDispatcher
 */
public final class DistanceAlphaCurve {

    private DistanceAlphaCurve() {
    }

    /**
     * Computes the opacity multiplier for a bullet at the given squared
     * distance from the camera.
     *
     * <p>{@code t = clamp(distance / farDistanceBlocks, 0, 1)} is passed
     * through {@code smoothstep(t) = t²(3 − 2t)} and mapped onto the
     * {@code [minAlpha, 1]} range:
     * {@code alpha = minAlpha + (1 − minAlpha) × smoothstep(t)}.</p>
     *
     * @param distanceSqr      the squared distance (blocks²) from the camera
     *                         to the bullet — the form
     *                         {@code Vec3.distanceToSqr} returns
     * @param farDistanceBlocks the distance at which the bullet becomes fully
     *                         opaque; must be {@code > 0}
     * @param minAlpha         the opacity at distance {@code 0}, in
     *                         {@code [0, 1]} (the client config enforces
     *                         {@code 0.05–1.0}; {@code 1.0} disables the fade)
     * @return the opacity multiplier in {@code [minAlpha, 1]}
     */
    public static float computeAlpha(double distanceSqr, double farDistanceBlocks, float minAlpha) {
        double distance = Math.sqrt(distanceSqr);
        double t = Math.min(1.0, Math.max(0.0, distance / farDistanceBlocks));
        double smoothstep = t * t * (3.0 - 2.0 * t);
        return (float) (minAlpha + (1.0 - minAlpha) * smoothstep);
    }
}
