package org.yanbwe.modularshoot.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Server-side shoot animation state tracker and synchronization broadcaster
 * (设计文档 §第三人称射击动画实现, §isFiring 标记维护).
 *
 * <p>Maintains per-player "recently shooting" state on the server and
 * broadcasts {@link ShootAnimS2CPacket}s to nearby clients so that remote
 * players see the third-person shoot animation and the {@code while_firing}
 * texture mode. The server is the authority for remote-player visibility;
 * the local player's own state is maintained client-side with zero delay
 * (see {@link org.yanbwe.modularshoot.client.PlayerShootStateManager}).</p>
 *
 * <p><b>State machine (per player):</b></p>
 * <ul>
 *   <li>Receiving a {@code ShootC2SPacket} sets {@code isFiring=true} and
 *       resets the 2-tick timeout counter (see
 *       {@link #onShootPacketReceived}).</li>
 *   <li>When a shot actually fires (shooting step 8), the animation timer is
 *       set to the peak and a broadcast is sent to nearby clients (see
 *       {@link #onShootFired}).</li>
 *   <li>Each server tick, the timeout counter increments; when it reaches
 *       {@value #FIRING_TIMEOUT_TICKS} ticks without a new shoot packet,
 *       {@code isFiring} flips to {@code false} and the transition is
 *       broadcast. The timer also decays by 1 per tick.</li>
 * </ul>
 *
 * <p><b>Broadcast target:</b> the shooter is excluded from the broadcast
 * (they maintain their own state with zero delay), so only nearby remote
 * clients receive the packet.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus without a
 * side filter. {@link ServerTickEvent.Post} only fires on the logical server,
 * so the handler is effectively server-only.</p>
 *
 * @see ShootAnimS2CPacket
 * @see org.yanbwe.modularshoot.client.PlayerShootStateManager
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ShootAnimSyncService {

    /** Peak animation timer value (in ticks) set when a shot fires. */
    public static final float SHOOT_ANIM_PEAK = 5.0f;

    /** Number of ticks without a shoot packet before a player is considered to have stopped firing. */
    public static final int FIRING_TIMEOUT_TICKS = 2;

    /** Radius (in blocks) within which clients receive the animation broadcast. */
    public static final double BROADCAST_RADIUS = 64.0;

    private static final ShootAnimSyncService INSTANCE = new ShootAnimSyncService();

    private final Map<UUID, ServerShootState> states = new HashMap<>();

    private ShootAnimSyncService() {
    }

    /**
     * {@return the singleton server-side sync service instance}
     */
    public static ShootAnimSyncService getInstance() {
        return INSTANCE;
    }

    /**
     * Called when a {@code ShootC2SPacket} is received from a player.
     *
     * <p>Marks the player as firing and resets the 2-tick timeout counter.
     * This is invoked <em>before</em> shot validation so that the firing flag
     * reflects "the player is actively requesting shots" rather than "a shot
     * actually fired" &mdash; matching the design doc's "收到 ShootC2SPacket
     * 即置 true" contract.</p>
     *
     * @param player the shooting server player; must not be {@code null}
     */
    public void onShootPacketReceived(ServerPlayer player) {
        ServerShootState state = getOrCreate(player.getUUID());
        state.isFiring = true;
        state.ticksSinceLastPacket = 0;
    }

    /**
     * Called when a shot actually fires (shooting step 8) to set the
     * animation timer to the peak and broadcast the state to nearby clients.
     *
     * <p>The shooter is excluded from the broadcast &mdash; they maintain
     * their own timer with zero delay on the client. Only nearby remote
     * clients receive the packet, so they can play the third-person animation
     * for this player.</p>
     *
     * @param player the shooting server player; must not be {@code null}
     */
    public void onShootFired(ServerPlayer player) {
        ServerShootState state = getOrCreate(player.getUUID());
        state.isFiring = true;
        state.shootAnimTimer = SHOOT_ANIM_PEAK;
        broadcast(player, state);
    }

    /**
     * Per-server-tick hook: advances the timeout counter for every online
     * player, flips {@code isFiring} to {@code false} (with a broadcast) when
     * the timeout is reached, and decays the animation timer.
     *
     * @param event the post server-tick event
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ShootAnimSyncService mgr = getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            mgr.tickPlayer(player);
        }
    }

    /**
     * Removes the per-player state entry when a player logs out, preventing
     * unbounded growth of the {@link #states} map.
     *
     * <p>Without this hook every player who ever fires a shot would leave a
     * residual entry in {@code states} forever, since the timeout counter in
     * {@link #tickPlayer} only flips {@code isFiring} to {@code false} but
     * never removes the entry. On a long-running server this causes a
     * steadily growing memory leak (内存泄漏修复 K12).</p>
     *
     * <p>Mirrors the cleanup pattern in
     * {@link GunSyncService#onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent)}
     * and
     * {@link BulletSyncService#onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent)},
     * both of which remove the disconnecting player's entries from their
     * respective state maps on the same event.</p>
     *
     * @param event the player-logged-out event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.getEntity().getUUID();
        getInstance().states.remove(playerUuid);
    }

    /**
     * Advances the state for a single player: increments the timeout counter,
     * flips {@code isFiring} on timeout (with broadcast), and decays the
     * timer.
     *
     * @param player the online server player
     */
    private void tickPlayer(ServerPlayer player) {
        ServerShootState state = states.get(player.getUUID());
        if (state == null) {
            return;
        }
        state.ticksSinceLastPacket++;
        if (state.ticksSinceLastPacket >= FIRING_TIMEOUT_TICKS && state.isFiring) {
            state.isFiring = false;
            broadcast(player, state);
        }
        if (state.shootAnimTimer > 0f) {
            state.shootAnimTimer -= 1f;
        }
    }

    /**
     * Broadcasts a {@link ShootAnimS2CPacket} carrying the player's current
     * state to all clients within {@link #BROADCAST_RADIUS} of the player,
     * excluding the shooter.
     *
     * @param player the shooting server player
     * @param state  the player's current server-side state
     */
    private void broadcast(ServerPlayer player, ServerShootState state) {
        Vec3 pos = player.position();
        ShootAnimS2CPacket packet = new ShootAnimS2CPacket(
                player.getUUID(), state.isFiring, state.shootAnimTimer);
        PacketDistributor.sendToPlayersNear(
                player.serverLevel(), player, pos.x, pos.y, pos.z, BROADCAST_RADIUS, packet);
    }

    /**
     * Returns the existing state for the given UUID, or creates a new default
     * entry if none exists.
     *
     * @param uuid the player UUID
     * @return the state entry (never {@code null})
     */
    private ServerShootState getOrCreate(UUID uuid) {
        return states.computeIfAbsent(uuid, k -> new ServerShootState());
    }

    /**
     * Clears all per-player server state.
     */
    public void clear() {
        states.clear();
    }

    /**
     * Mutable per-player server-side shoot state.
     *
     * <p>Mutated in place every tick by {@link #tickPlayer} to avoid per-tick
     * allocation in the server tick loop. Fields are accessed directly by the
     * enclosing service.</p>
     */
    private static final class ServerShootState {
        private boolean isFiring;
        private int ticksSinceLastPacket;
        private float shootAnimTimer;
    }
}
