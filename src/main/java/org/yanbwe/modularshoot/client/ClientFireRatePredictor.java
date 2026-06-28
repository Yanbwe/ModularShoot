package org.yanbwe.modularshoot.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;

/**
 * Client-side fire-rate predictor that mirrors the server's
 * {@link org.yanbwe.modularshoot.shooting.FireRateController} logic to predict
 * <em>when a shot should visually fire</em> for the local player.
 *
 * <p>The server is the sole authority on actual shot timing: it runs
 * {@code FireRateController.canShoot} per incoming {@code ShootC2SPacket}, and
 * broadcasts the result to <em>remote</em> clients via
 * {@link org.yanbwe.modularshoot.network.ShootAnimS2CPacket}. The local
 * player, however, is deliberately excluded from that broadcast (they maintain
 * their own state with zero delay), so the client must predict shot timing
 * locally to drive the {@code per_shot} texture mode and the arm-recoil
 * animation with the same per-shot cadence the server enforces.</p>
 *
 * <p><b>Prediction accuracy:</b> the {@code fire_rate} attribute is
 * {@code syncable} (see {@link ModularShootAttributes}), so the client sees the
 * same final value the server uses. The interval formula
 * {@code max(1, round(20 / fireRate))} is replicated verbatim from
 * {@code FireRateController.computeInterval}. Minor tick-skew between client
 * and server is acceptable: this predictor drives only <em>visuals</em>
 * (texture flash + arm pose); it never decides whether a bullet actually
 * spawns &mdash; that remains the server's exclusive responsibility.</p>
 *
 * <p><b>State:</b> a {@code player uuid &rarr; (gun id &rarr; last shoot tick)}
 * map, mirroring {@code FireRateController}'s structure. The state lives only
 * on the client and is mutated solely on the client tick thread.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see org.yanbwe.modularshoot.shooting.FireRateController
 * @see PlayerShootStateManager#updateLocalPlayer
 */
public final class ClientFireRatePredictor {

    /** Vanilla tick rate (ticks per second), used to convert shots/s into a tick interval. */
    private static final int TICKS_PER_SECOND = 20;

    /** Per-player, per-gun last predicted shoot tick: player uuid &rarr; (gun id &rarr; last shoot tick). */
    private static final Map<UUID, Map<ResourceLocation, Long>> lastShootTicks = new HashMap<>();

    private ClientFireRatePredictor() {
    }

    /**
     * Predicts whether a shot should visually fire this tick for the given
     * local player, mirroring the server's
     * {@link org.yanbwe.modularshoot.shooting.FireRateController#canShoot}
     * gate.
     *
     * <p>Reads the player's final {@code fire_rate} attribute and the main-hand
     * gun id, computes the tick interval, and compares it against the last
     * predicted shoot tick. When the interval has elapsed the last-shoot tick
     * is updated and {@code true} is returned; otherwise the last-shoot tick is
     * left unchanged and {@code false} is returned.</p>
     *
     * <p>Callers must ensure the player is actually holding a gun and pressing
     * the attack key before invoking this method &mdash; it performs no
     * trigger / item guard of its own, only the fire-rate gate.</p>
     *
     * @param player the local player; must not be {@code null}
     * @return {@code true} if a shot should visually fire this tick under the
     *         predicted fire-rate limit
     */
    public static boolean shouldPredictShoot(Player player) {
        double fireRate = player.getAttributeValue(ModularShootAttributes.FIRE_RATE);
        if (fireRate <= 0.0) {
            return false;
        }
        ResourceLocation gunId = readGunId(player);
        if (gunId == null) {
            return false;
        }
        long currentTick = player.level().getGameTime();
        int interval = computeInterval(fireRate);
        Long lastTick = getRecordedLastShootTick(player.getUUID(), gunId);
        if (lastTick != null && currentTick - lastTick < interval) {
            return false;
        }
        setLastShootTick(player.getUUID(), gunId, currentTick);
        return true;
    }

    /**
     * Reads the gun-definition id from the player's main-hand stack.
     *
     * @param player the player to inspect
     * @return the gun id, or {@code null} when the main-hand item is not a gun
     *         or carries no {@code gun_data} component
     */
    private static @Nullable ResourceLocation readGunId(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return null;
        }
        GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunId();
    }

    /**
     * Computes the fire-rate interval in ticks:
     * {@code max(1, round(20 / fireRate))}.
     *
     * <p>Replicated verbatim from
     * {@link org.yanbwe.modularshoot.shooting.FireRateController#computeInterval}
     * so the client's predicted cadence matches the server's actual cadence.
     * Any change to the server formula must be mirrored here.</p>
     *
     * @param fireRate the fire-rate attribute value (shots per second); must be {@code > 0}
     * @return the minimum number of ticks between two predicted shots
     */
    private static int computeInterval(double fireRate) {
        return Math.max(1, (int) Math.round(TICKS_PER_SECOND / fireRate));
    }

    /**
     * Returns the last recorded predicted shoot tick for the given player and
     * gun, or {@code null} when no shot has been predicted yet.
     *
     * @param playerId the player uuid
     * @param gunId    the gun definition id
     * @return the last predicted shoot tick, or {@code null} when none recorded
     */
    private static @Nullable Long getRecordedLastShootTick(UUID playerId, ResourceLocation gunId) {
        Map<ResourceLocation, Long> perPlayer = lastShootTicks.get(playerId);
        return perPlayer == null ? null : perPlayer.get(gunId);
    }

    /**
     * Records the last predicted shoot tick for the given player and gun,
     * creating the per-player sub-map on first access.
     *
     * @param playerId the player uuid
     * @param gunId    the gun definition id
     * @param tick     the tick at which the shot was predicted
     */
    private static void setLastShootTick(UUID playerId, ResourceLocation gunId, long tick) {
        lastShootTicks.computeIfAbsent(playerId, k -> new HashMap<>()).put(gunId, tick);
    }

    /**
     * Clears all predicted fire-rate state for the given player.
     *
     * <p>Should be called when the local player disconnects to avoid retaining
     * per-player state indefinitely. Safe to call even when no state exists.</p>
     *
     * @param playerId the player uuid to clear; must not be {@code null}
     */
    public static void clearPlayer(UUID playerId) {
        lastShootTicks.remove(playerId);
    }

    /**
     * Clears all predicted fire-rate state.
     */
    public static void clearAll() {
        lastShootTicks.clear();
    }
}
