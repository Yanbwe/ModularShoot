package org.yanbwe.modularshoot.shooting;

/**
 * Shared fire-rate interval maths (计划 §阶段 6 / 任务 6.4).
 *
 * <p>Single source of truth for converting the {@code fire_rate} attribute
 * value (shots per second) into a minimum tick interval between two shots.
 * The server-authoritative {@link FireRateController} and the client-side
 * {@code org.yanbwe.modularshoot.client.ClientFireRatePredictor} both delegate
 * to {@link #computeInterval(double)} so the visual cadence predicted on the
 * client always matches the actual cadence the server enforces, with exactly
 * one formula to maintain.</p>
 */
public final class FireRateMath {

    /** Vanilla tick rate (ticks per second), used to convert shots/s into a tick interval. */
    public static final int TICKS_PER_SECOND = 20;

    private FireRateMath() {
    }

    /**
     * Computes the fire-rate interval in ticks:
     * {@code max(1, round(20 / fireRate))}.
     *
     * <p>The minimum of {@code 1} tick ensures that even very high fire rates
     * cannot fire more than once per tick. {@link Math#round(double)} returns
     * a {@code long} which is narrowed to {@code int} after the {@code max}
     * clamp; the result always fits because {@code fireRate} is positive.</p>
     *
     * <p><b>Attribute range vs. effective fire rate (W18 fix).</b> The
     * {@code fire_rate} attribute is registered with a clamp of {@code [0, 1024]},
     * but the <em>effective</em> fire rate is bounded by the server tick rate
     * of {@link #TICKS_PER_SECOND} ticks/s: any {@code fire_rate > TPS} yields
     * {@code round(20 / fireRate) = 0}, which the {@code max(1, ...)} clamp
     * raises to a 1-tick interval. A {@code fire_rate <= 0} is rejected earlier
     * by the callers, so this method only ever sees positive values and always
     * returns a well-defined {@code >= 1} interval.</p>
     *
     * @param fireRate the fire-rate attribute value (shots per second); must be {@code > 0}
     * @return the minimum number of ticks between two shots
     */
    public static int computeInterval(double fireRate) {
        return Math.max(1, (int) Math.round(TICKS_PER_SECOND / fireRate));
    }
}
