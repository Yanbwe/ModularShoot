package org.yanbwe.modularshoot.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.network.ShootAnimS2CPacket;
import org.yanbwe.modularshoot.network.ShootAnimSyncService;

/**
 * Client-side per-player shoot animation state manager (设计文档 §第三人称射击动画实现).
 *
 * <p>Maintains two independent pieces of per-player state used to drive the
 * third-person shoot animation and the {@code while_firing} texture mode:</p>
 * <ul>
 *   <li><b>{@code isFiring}</b> &mdash; a boolean flag indicating whether the
 *       player is currently holding the attack key with a gun in the main
 *       hand. This drives the {@code while_firing} shoot-texture selection
 *       mode and is deliberately kept <em>independent</em> from the animation
 *       timer (see design doc §isFiring 标记维护 for the rationale: the timer
 *       peaks for only a few ticks and decays quickly, so reusing it for
 *       low-fire-rate guns would cause texture flicker between shots).</li>
 *   <li><b>{@code shootAnimTimer}</b> &mdash; a float timer (in ticks) that
 *       drives the Mixin-injected arm-recoil animation and the
 *       {@code per_shot} shoot-texture selection mode. It decays by 1 per
 *       tick and is reset to the peak value only when a shot actually fires
 *       (for remote players, on {@link ShootAnimS2CPacket} arrival; for the
 *       local player, when {@link ClientFireRatePredictor} predicts a shot),
 *       so it pulses per round rather than being held continuously.</li>
 * </ul>
 *
 * <p><b>Local player (zero-delay):</b> the local player's state is maintained
 * directly on the client with zero latency &mdash; {@code isFiring} is
 * derived from the attack-key + main-hand-gun condition every tick (matching
 * {@code ShootC2SPacket}'s send condition), and {@code shootAnimTimer} is
 * reset to the peak only when {@link ClientFireRatePredictor} predicts a shot
 * should fire this tick (mirroring the server's
 * {@code FireRateController} gate), without waiting for a server round-trip.
 * The timer is <em>not</em> reset every tick while the attack key is held, so
 * it decays between shots and the {@code per_shot} texture mode flashes per
 * round instead of being held continuously.</p>
 *
 * <p><b>Other players (server-synced):</b> the state of remote players is
 * updated when a {@link ShootAnimS2CPacket} arrives, carrying the server's
 * view of that player's {@code isFiring} and {@code shootAnimTimer}. This
 * path has a network RTT delay, consistent with the sound/bullet visual
 * delay, which is acceptable per the design doc.</p>
 *
 * <p><b>Tick decay:</b> every client tick, all timers are decremented by 1
 * (stopping at 0). The decay runs <em>before</em> the local-player update so
 * that a predicted shot resets the timer to the peak <em>after</em> the decay,
 * yielding a full-strength pulse on the shot tick and a clean per-shot decay
 * afterwards &mdash; matching the remote-player cadence driven by
 * {@link ShootAnimS2CPacket}.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus with
 * {@code value = Dist.CLIENT} so the class is only loaded on the physical
 * client, preventing {@code ClassNotFoundException} for {@link Minecraft} on
 * a dedicated server.</p>
 *
 * @see ShootAnimS2CPacket
 * @see ShootAnimSyncService
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class PlayerShootStateManager {

    private static final PlayerShootStateManager INSTANCE = new PlayerShootStateManager();

    /** Default state returned for players with no recorded entry. */
    private static final PlayerShootState DEFAULT_STATE = new PlayerShootState(false, 0f);

    private final Map<UUID, PlayerShootState> states = new HashMap<>();

    private PlayerShootStateManager() {
    }

    /**
     * {@return the singleton client-side state manager instance}
     */
    public static PlayerShootStateManager getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the shoot state for the given player, or a default
     * ({@code isFiring=false, shootAnimTimer=0}) state when no entry exists.
     *
     * @param playerUuid the player UUID; must not be {@code null}
     * @return the player's shoot state (never {@code null})
     */
    public PlayerShootState getState(UUID playerUuid) {
        return states.getOrDefault(playerUuid, DEFAULT_STATE);
    }

    /**
     * Sets the firing flag for the given player, preserving the current
     * animation timer.
     *
     * @param playerUuid the player UUID
     * @param firing     the new firing flag value
     */
    public void setFiring(UUID playerUuid, boolean firing) {
        PlayerShootState current = states.get(playerUuid);
        if (current == null) {
            if (!firing) {
                return; // default state is already not-firing; no entry needed
            }
            states.put(playerUuid, new PlayerShootState(true, 0f));
        } else {
            states.put(playerUuid, new PlayerShootState(firing, current.shootAnimTimer()));
        }
    }

    /**
     * Sets the animation timer for the given player, preserving the current
     * firing flag.
     *
     * @param playerUuid the player UUID
     * @param timer      the new timer value (in ticks)
     */
    public void setAnimTimer(UUID playerUuid, float timer) {
        PlayerShootState current = states.get(playerUuid);
        if (current == null) {
            if (timer <= 0f) {
                return; // default state is already 0; no entry needed
            }
            states.put(playerUuid, new PlayerShootState(false, timer));
        } else {
            states.put(playerUuid, new PlayerShootState(current.isFiring(), timer));
        }
    }

    /**
     * {@return the animation timer for the given player, or {@code 0} when no entry exists}
     *
     * @param playerUuid the player UUID
     */
    public float getAnimTimer(UUID playerUuid) {
        return getState(playerUuid).shootAnimTimer();
    }

    /**
     * {@return whether the given player is currently in the firing state}
     *
     * @param playerUuid the player UUID
     */
    public boolean isFiring(UUID playerUuid) {
        return getState(playerUuid).isFiring();
    }

    /**
     * Decays every player's animation timer by 1 tick, stopping at 0.
     *
     * <p>The firing flag is <em>not</em> touched here &mdash; it is
     * independent of the timer and only changes via {@link #setFiring} or
     * {@link #handlePacket}.</p>
     */
    public void tickDecay() {
        states.replaceAll((uuid, state) -> {
            if (state.shootAnimTimer() > 0f) {
                return new PlayerShootState(state.isFiring(), state.shootAnimTimer() - 1f);
            }
            return state;
        });
    }

    /**
     * Clears all per-player state.
     */
    public void clear() {
        states.clear();
    }

    /**
     * Updates a remote player's state from an incoming
     * {@link ShootAnimS2CPacket}.
     *
     * <p>Packets for the local player are ignored &mdash; the local player's
     * state is maintained with zero delay by {@link #updateLocalPlayer}. Only
     * remote-player states are applied from the network.</p>
     *
     * @param packet the incoming shoot-animation packet
     */
    public void handlePacket(ShootAnimS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && packet.playerUuid().equals(minecraft.player.getUUID())) {
            return; // local player state is maintained locally with zero delay
        }
        states.put(packet.playerUuid(), new PlayerShootState(packet.isFiring(), packet.shootAnimTimer()));
    }

    // --- Client tick handling --------------------------------------------

    /**
     * Per-client-tick hook: decays all timers, then updates the local
     * player's firing flag and timer.
     *
     * <p>The decay runs <em>first</em> so that a predicted shot resets the
     * timer to the peak <em>after</em> the decay &mdash; yielding a
     * full-strength pulse on the shot tick and a clean per-shot decay
     * afterwards, matching the remote-player cadence.</p>
     *
     * @param event the pre client-tick event (unused beyond its presence)
     */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        PlayerShootStateManager mgr = getInstance();
        mgr.tickDecay();
        mgr.updateLocalPlayer(minecraft);
    }

    /**
     * Updates the local player's {@code isFiring} flag and, when a shot is
     * predicted to fire this tick, resets the animation timer to the peak for
     * zero-delay visual feedback.
     *
     * <p>The firing condition mirrors {@code ClientShootSender}'s
     * {@code ShootC2SPacket} send condition (attack key held + main-hand
     * gun), so the {@code isFiring} flag stays in sync with the shoot
     * requests and drives the {@code while_firing} texture mode.</p>
     *
     * <p>The animation timer is <em>not</em> reset every tick while firing.
     * Instead it is reset only when {@link ClientFireRatePredictor} predicts
     * that a shot should visually fire this tick (mirroring the server's
     * {@code FireRateController} gate). Between predicted shots the timer
     * decays normally, so the {@code per_shot} texture mode flashes per round
     * rather than being held continuously &mdash; matching the remote-player
     * behaviour driven by {@link ShootAnimS2CPacket}. This also gives the
     * local player's arm-recoil animation the same per-shot cadence as remote
     * players, instead of a flat full-strength hold.</p>
     *
     * @param minecraft the client instance (with a non-null player)
     */
    private void updateLocalPlayer(Minecraft minecraft) {
        Player player = minecraft.player;
        UUID uuid = player.getUUID();
        boolean firing = minecraft.options.keyAttack.isDown() && isMainHandGun(player);
        setFiring(uuid, firing);
        if (firing && ClientFireRatePredictor.shouldPredictShoot(player)) {
            setAnimTimer(uuid, ShootAnimSyncService.SHOOT_ANIM_PEAK);
        }
    }

    /**
     * Checks whether the player's main-hand item is a framework gun.
     *
     * @param player the player to inspect
     * @return {@code true} when the main-hand stack is a {@code modularshoot:gun}
     */
    private static boolean isMainHandGun(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        return ModularShootAPI.isGun(mainHand);
    }

    /**
     * Immutable per-player shoot animation state.
     *
     * @param isFiring       whether the player is in the firing state
     * @param shootAnimTimer the animation timer (ticks, decays to 0)
     */
    public record PlayerShootState(boolean isFiring, float shootAnimTimer) {
    }
}
