package org.yanbwe.modularshoot.shooting;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Server-authoritative fire-rate controller driven by the global server tick
 * counter.
 *
 * <p>Implements the fire-rate gate from 设计文档 §步骤二（射速控制）. The server
 * is the sole authority on when a shot may fire: every
 * {@link org.yanbwe.modularshoot.network.ShootC2SPacket ShootC2SPacket} is
 * checked against a per-player, per-gun last-shoot tick, and the request is
 * rejected when it arrives before the computed tick interval has elapsed.</p>
 *
 * <p><b>Interval calculation:</b> the {@code fire_rate} attribute is expressed
 * in shots per second. The tick interval is
 * {@code max(1, round(20 / fire_rate))}. A {@code fire_rate} of {@code 0} or
 * below means the gun never fires &mdash; the interval is treated as infinite,
 * which also avoids division by zero.</p>
 *
 * <p><b>Tick-driven, not wall-clock:</b> the counter comes from
 * {@link MinecraftServer#getTickCount()}, so when the server TPS drops the
 * actual fire rate drops proportionally. The framework never performs
 * wall-clock-based catch-up; a dropped packet simply means one fewer shot,
 * with no cascading desync (设计文档 §网络包设计).</p>
 *
 * <p><b>State:</b> a {@code player uuid &rarr; (gun id &rarr; last shoot tick)}
 * map tracks the last accepted shot per player per gun. The state is mutated
 * only on the main game thread (NeoForge runs payload handlers there), so a
 * plain {@link HashMap} is sufficient.</p>
 */
public final class FireRateController {

    /** Vanilla tick rate (ticks per second), used to convert shots/s into a tick interval. */
    private static final int TICKS_PER_SECOND = 20;

    /** Per-player, per-gun last shoot tick: player uuid &rarr; (gun id &rarr; last shoot tick). */
    private static final Map<UUID, Map<ResourceLocation, Integer>> lastShootTicks = new HashMap<>();

    private FireRateController() {
    }

    /**
     * Checks whether the player may fire the given gun this tick under the
     * fire-rate limit, and records the current tick as the last-shoot tick
     * when the shot is allowed.
     *
     * <p>When the shot is rejected (interval not yet elapsed, non-positive
     * fire rate, or no server available) the last-shoot tick is left
     * unchanged, so a later valid request is not penalised for an earlier
     * rejected one.</p>
     *
     * @param player   the shooting player; must not be {@code null}
     * @param gunId    the gun definition id of the main-hand gun; must not be
     *                 {@code null}
     * @param fireRate the final {@code fire_rate} attribute value (shots per
     *                 second); {@code <= 0} means the gun never fires
     * @return {@code true} if the shot may proceed (and the last-shoot tick
     *         has been updated); {@code false} if the request must be dropped
     */
    public static boolean canShoot(ServerPlayer player, ResourceLocation gunId, double fireRate) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gunId, "gunId");
        if (fireRate <= 0.0) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        int currentTick = server.getTickCount();
        int interval = computeInterval(fireRate);
        Integer lastTick = getRecordedLastShootTick(player.getUUID(), gunId);
        if (lastTick != null && currentTick - lastTick < interval) {
            return false;
        }
        setLastShootTick(player.getUUID(), gunId, currentTick);
        return true;
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
     * {@code fire_rate} attribute is registered in
     * {@link org.yanbwe.modularshoot.attribute.ModularShootAttributes} with a
     * {@link net.minecraft.world.entity.ai.attributes.RangedAttribute} clamp
     * of {@code [0, 1024]} &mdash; that is the range the attribute value may
     * hold after modifier aggregation. The <em>effective</em> fire rate,
     * however, is bounded by the server tick rate of 20 ticks/s: any
     * {@code fire_rate > 20} yields {@code round(20 / fireRate) = 0}, which the
     * {@code max(1, ...)} clamp raises to a 1-tick interval, i.e. at most 20
     * shots/s in practice (设计文档 §属性表: "实际参与计算的值 clamp 到
     * (0, 20]"). A {@code fire_rate <= 0} is rejected earlier in
     * {@link #canShoot}. This method therefore only ever sees positive values
     * and always returns a well-defined {@code >= 1} interval.</p>
     *
     * @param fireRate the fire-rate attribute value (shots per second); must be {@code > 0}
     * @return the minimum number of ticks between two shots
     */
    private static int computeInterval(double fireRate) {
        return Math.max(1, (int) Math.round(TICKS_PER_SECOND / fireRate));
    }

    /**
     * Returns the last recorded shoot tick for the given player and gun, or
     * {@code null} when the player has never fired this gun.
     *
     * @param playerId the player uuid
     * @param gunId    the gun definition id
     * @return the last shoot tick, or {@code null} when no shot has been recorded
     */
    private static @Nullable Integer getRecordedLastShootTick(UUID playerId, ResourceLocation gunId) {
        Map<ResourceLocation, Integer> perPlayer = lastShootTicks.get(playerId);
        return perPlayer == null ? null : perPlayer.get(gunId);
    }

    /**
     * Records the last shoot tick for the given player and gun, creating the
     * per-player sub-map on first access.
     *
     * @param playerId the player uuid
     * @param gunId    the gun definition id
     * @param tick     the tick at which the shot was accepted
     */
    private static void setLastShootTick(UUID playerId, ResourceLocation gunId, int tick) {
        lastShootTicks.computeIfAbsent(playerId, k -> new HashMap<>()).put(gunId, tick);
    }

    /**
     * Clears all fire-rate state for the given player.
     *
     * <p>Should be called when the player disconnects to avoid retaining
     * per-player state indefinitely. Safe to call even when no state exists.</p>
     *
     * @param playerId the player uuid to clear; must not be {@code null}
     */
    public static void clearPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        lastShootTicks.remove(playerId);
    }
}
