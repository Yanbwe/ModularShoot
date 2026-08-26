package org.yanbwe.modularshoot.network;

/**
 * Pure fixed-point quantization / threshold / distance-decimation rules for
 * the bullet incremental-sync path (阶段 2 / 任务 2.2).
 *
 * <p><b>Purpose.</b> The previous incremental sync used an epsilon of
 * {@code 1e-6} for change detection, so a bullet whose position/direction
 * drifted even sub-pixel amounts would send a delta entry <em>every tick</em>
 * to every player in range. This class replaces that with a coarser, explicit
 * policy that sharply cuts per-tick bandwidth:</p>
 * <ul>
 *   <li><b>Fixed-point position quantization (1/128 block).</b> A delta is
 *       sent only when the bullet crosses a {@code 1/128} block cell boundary,
 *       so sub-pixel / tiny per-tick drift no longer produces a packet every
 *       tick. The {@link BulletSyncService} still stores the <em>full
 *       precision</em> last-sent position, so the comparison base never loses
 *       precision; only the <em>decision</em> is made on the quantized grid.</li>
 *   <li><b>Direction quantization (1/128).</b> The same fixed-point grid is
 *       applied to the normalized direction components, so direction noise
 *       does not spam deltas either.</li>
 *   <li><b>Distance-based update decimation.</b> Bullets farther from the
 *       player are throttled to a lower maximum update frequency (a minimum
 *       tick interval per distance band), trading a little positional latency
 *       at long range for bandwidth. Range-based throttling composes with
 *       quantization: a delta is sent only when a change crossed a cell
 *       boundary <em>and</em> the distance band allows an update this tick.</li>
 * </ul>
 *
 * <p>This class is deliberately <em>stateless</em>: every method is a pure
 * function of its arguments, so the rules are unit-testable and the service
 * keeps the per-bullet state (full-precision last-sent values + last-sent
 * tick) in its own {@code BulletState} record.</p>
 */
final class BulletDeltaQuantizer {

    /**
     * Fixed-point position quantum: 1/128 block (≈0.0078125 blocks). Chosen
     * because it is far below render scale (a bullet at 1/128-block resolution
     * is imperceptibly coarser than full float precision) yet coarse enough to
     * suppress per-tick sub-pixel drift spam.
     */
    static final double POSITION_QUANTUM = 1.0 / 128.0;

    /** Fixed-point direction quantum: 1/128 of a unit direction component. */
    static final double DIRECTION_QUANTUM = 1.0 / 128.0;

    /**
     * Distance bands (in blocks, horizontal) that map a bullet to a maximum
     * delta-update frequency. Within the close band a bullet may update every
     * tick; the mid band allows at most one update every
     * {@link #MID_INTERVAL_TICKS} ticks; beyond the mid band at most one
     * every {@link #FAR_INTERVAL_TICKS} ticks.
     *
     * <p>These are the <em>defaults</em>; at runtime the sync service passes
     * the live config values from
     * {@link org.yanbwe.modularshoot.config.ModularShootCommonConfig} through
     * the parameterised overloads (审查 O7). Unit tests exercise the default
     * constants via the parameter-less methods.</p>
     */
    static final double CLOSE_DISTANCE = 32.0;
    static final double MID_DISTANCE = 64.0;
    static final int MID_INTERVAL_TICKS = 2;
    static final int FAR_INTERVAL_TICKS = 4;

    private BulletDeltaQuantizer() {
    }

    /**
     * Quantizes a world-space coordinate to the nearest {@link #POSITION_QUANTUM}
     * cell. Values are rounded to the nearest cell (Math.round), matching the
     * "send only when the quantized value changes" semantics.
     *
     * @param value the full-precision coordinate
     * @return the nearest fixed-point grid value (still a {@code double})
     */
    static double quantizePosition(double value) {
        return Math.round(value / POSITION_QUANTUM) * POSITION_QUANTUM;
    }

    /**
     * Returns whether any position component crossed a quantization cell
     * boundary between {@code last} and {@code cur}. Identical (or
     * within-cell) positions return {@code false}, so sub-quantum drift does
     * not produce a delta.
     *
     * @param curX current x
     * @param curY current y
     * @param curZ current z
     * @param lastX last-sent x
     * @param lastY last-sent y
     * @param lastZ last-sent z
     * @return {@code true} if a delta should be sent for the position
     */
    static boolean positionChanged(
            double curX, double curY, double curZ,
            double lastX, double lastY, double lastZ) {
        return quantizePosition(curX) != quantizePosition(lastX)
                || quantizePosition(curY) != quantizePosition(lastY)
                || quantizePosition(curZ) != quantizePosition(lastZ);
    }

    /**
     * Returns whether any direction component crossed a direction-quantum
     * cell boundary between {@code last} and {@code cur}. Direction components
     * are normalized and lie in {@code [-1, 1]}, so the {@code 1/128} grid is
     * a small angular step well below render sensitivity.
     *
     * @param curX current direction x
     * @param curY current direction y
     * @param curZ current direction z
     * @param lastX last-sent direction x
     * @param lastY last-sent direction y
     * @param lastZ last-sent direction z
     * @return {@code true} if a delta should be sent for the direction
     */
    static boolean directionChanged(
            double curX, double curY, double curZ,
            double lastX, double lastY, double lastZ) {
        return quantizeDirection(curX) != quantizeDirection(lastX)
                || quantizeDirection(curY) != quantizeDirection(lastY)
                || quantizeDirection(curZ) != quantizeDirection(lastZ);
    }

    private static double quantizeDirection(double value) {
        return Math.round(value / DIRECTION_QUANTUM) * DIRECTION_QUANTUM;
    }

    /**
     * Returns the minimum number of server ticks that must elapse between
     * delta updates for a bullet at the given (horizontal) distance from the
     * player. Near bullets return {@code 1} (eligible every tick); mid and far
     * bullets return progressively larger intervals.
     *
     * @param distance the bullet's distance to the player in blocks
     * @return the minimum tick interval between delta sends for this bullet
     */
    static int updateIntervalTicks(double distance) {
        return updateIntervalTicks(distance,
                CLOSE_DISTANCE, MID_DISTANCE, MID_INTERVAL_TICKS, FAR_INTERVAL_TICKS);
    }

    /**
     * Parameterised variant of {@link #updateIntervalTicks(double)} accepting
     * the live config values (审查 O7). Same band semantics: close band
     * updates every tick, mid band every {@code midIntervalTicks}, far band
     * every {@code farIntervalTicks}.
     *
     * @param distance         the bullet's distance to the player in blocks
     * @param closeDistance    close-band radius (config)
     * @param midDistance      mid-band radius (config)
     * @param midIntervalTicks mid-band minimum interval (config)
     * @param farIntervalTicks far-band minimum interval (config)
     * @return the minimum tick interval between delta sends for this bullet
     */
    static int updateIntervalTicks(
            double distance, double closeDistance, double midDistance,
            int midIntervalTicks, int farIntervalTicks) {
        if (distance <= closeDistance) {
            return 1;
        }
        if (distance <= midDistance) {
            return midIntervalTicks;
        }
        return farIntervalTicks;
    }

    /**
     * Returns whether the bullet is allowed to send a delta <em>this tick</em>
     * given the distance band and the last time a delta was actually sent.
     *
     * @param distance    the bullet's distance to the player in blocks
     * @param currentTick the current server game time (ticks)
     * @param lastSentTick the server tick when the last delta was sent, or a
     *                     value {@code <= currentTick - interval} for a
     *                     never-sent bullet
     * @return {@code true} if the interval for this distance band has elapsed
     */
    static boolean isUpdateEligible(double distance, long currentTick, long lastSentTick) {
        return (currentTick - lastSentTick) >= updateIntervalTicks(distance);
    }

    /**
     * Parameterised variant of
     * {@link #isUpdateEligible(double, long, long)} accepting the live
     * config values (审查 O7).
     *
     * @param distance         the bullet's distance to the player in blocks
     * @param currentTick      the current server game time (ticks)
     * @param lastSentTick     the server tick when the last delta was sent
     * @param closeDistance    close-band radius (config)
     * @param midDistance      mid-band radius (config)
     * @param midIntervalTicks mid-band minimum interval (config)
     * @param farIntervalTicks far-band minimum interval (config)
     * @return {@code true} when the interval for this distance band has elapsed
     */
    static boolean isUpdateEligible(
            double distance, long currentTick, long lastSentTick,
            double closeDistance, double midDistance,
            int midIntervalTicks, int farIntervalTicks) {
        return (currentTick - lastSentTick) >= updateIntervalTicks(
                distance, closeDistance, midDistance, midIntervalTicks, farIntervalTicks);
    }
}
