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
 *       set to the peak (see {@link #onShootFired}).</li>
 *   <li>Each server tick, the timeout counter increments; when it reaches
 *       {@value #FIRING_TIMEOUT_TICKS} ticks without a new shoot packet,
 *       {@code isFiring} flips to {@code false}. The timer also decays by 1
 *       per tick.</li>
 * </ul>
 *
 * <p><b>Broadcast batching (阶段 2 / 任务 2.4 动画广播节流).</b> Broadcasts are
 * no longer emitted eagerly from {@code onShootFired} or from the timeout
 * flip. Instead they are deferred: each player is marked as needing a
 * broadcast (with the tick's peak timer recorded), and a single
 * {@link ServerTickEvent.Post} flush sends one {@link ShootAnimS2CPacket} per
 * pending player. This guarantees each player broadcasts <em>at most once per
 * tick</em>, and when the player fires many shots in one tick only the peak
 * animation timer is transmitted &mdash; collapsing O(shots) packets into one
 * per player per tick (最多 1 tick 延迟，符合要求).</p>
 *
 * <p><b>Broadcast target:</b> the shooter is excluded from the broadcast
 * (they maintain their own state with zero delay), so only nearby remote
 * clients receive the packet.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus without a
 * side filter. {@link ServerTickEvent.Post} only fires on the logical server,
 * so the handler is effectively server-only.</p>
 *
 * <p><b>Testability:</b> the bookkeeping (state + batching) is exposed through
 * package-private {@code UUID}-based overloads and
 * {@link #drainPendingBroadcasts()}, so the one-broadcast-per-tick and
 * peak-preservation contracts can be unit tested without a
 * {@link ServerPlayer} or the network stack.</p>
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

    ShootAnimSyncService() {
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
        onShootPacketReceived(player.getUUID());
    }

    /**
     * Package-private UUID overload used for pure unit testing.
     *
     * @param uuid the shooting player's UUID
     */
    void onShootPacketReceived(UUID uuid) {
        ServerShootState state = getOrCreate(uuid);
        state.isFiring = true;
        state.ticksSinceLastPacket = 0;
    }

    /**
     * Called when a shot actually fires (shooting step 8) to set the
     * animation timer to the peak and schedule the tick-end broadcast.
     *
     * <p>Marks the player as pending-broadcast for this tick and records the
     * peak timer; the packet itself is sent once at tick end
     * ({@link #onServerTickPost}). Multiple calls within the same tick keep
     * the peak value — later fires overwrite nothing except the (identical)
     * peak, and only one broadcast per player per tick is emitted.</p>
     *
     * @param player the shooting server player; must not be {@code null}
     */
    public void onShootFired(ServerPlayer player) {
        onShootFired(player.getUUID());
    }

    /**
     * Package-private UUID overload used for pure unit testing.
     *
     * @param uuid the firing player's UUID
     */
    void onShootFired(UUID uuid) {
        ServerShootState state = getOrCreate(uuid);
        state.isFiring = true;
        state.shootAnimTimer = SHOOT_ANIM_PEAK;
        state.pendingBroadcast = true;
        state.pendingAnimTimer = SHOOT_ANIM_PEAK;
    }

    /**
     * Per-server-tick hook: advances the timeout counter for every online
     * player, flips {@code isFiring} to {@code false} when the timeout is
     * reached, decays the animation timer, then flushes all pending
     * broadcasts — sending at most one {@link ShootAnimS2CPacket} per player.
     *
     * @param event the post server-tick event
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ShootAnimSyncService mgr = getInstance();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            mgr.advanceTick(player.getUUID());
        }
        mgr.flushAll(server);
    }

    /**
     * Advances the per-player state for one tick (pure: no network IO).
     *
     * @param uuid the online player's UUID
     */
    void advanceTick(UUID uuid) {
        ServerShootState state = states.get(uuid);
        if (state == null) {
            return;
        }
        state.ticksSinceLastPacket++;
        if (state.ticksSinceLastPacket >= FIRING_TIMEOUT_TICKS && state.isFiring) {
            state.isFiring = false;
            // Clear any peak recorded by a same-tick onShootFired so the merged
            // single packet is never a contradictory (false, PEAK): the timeout
            // flip carries the already-decayed live timer value, not the stale
            // shoot-tick peak (review fix: 同 tick 开火+超时翻转合并出矛盾状态).
            state.pendingAnimTimer = 0f;
            state.pendingBroadcast = true;
        }
        if (state.shootAnimTimer > 0f) {
            state.shootAnimTimer -= 1f;
        }
    }

    /**
     * Flushes all pending broadcasts for the current tick, sending one
     * {@link ShootAnimS2CPacket} per pending online player and clearing the
     * pending markers.
     *
     * @param server the server whose online players should receive broadcasts
     */
    private void flushAll(MinecraftServer server) {
        Map<UUID, PendingOutbound> pending = drainPendingBroadcasts();
        for (PendingOutbound out : pending.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(out.uuid());
            if (player != null) {
                send(player, new ShootAnimS2CPacket(out.uuid(), out.isFiring(), out.shootAnimTimer()));
            }
        }
    }

    /**
     * Removes the per-player state entry when a player logs out, preventing
     * unbounded growth of the {@link #states} map.
     *
     * <p>Without this hook every player who ever fires a shot would leave a
     * residual entry in {@code states} forever, since the timeout counter in
     * {@link #advanceTick} only flips {@code isFiring} to {@code false} but
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
     * Collects and clears the pending broadcasts for the current tick: at
     * most one {@link PendingOutbound} per player, carrying the peak animation
     * timer when a shot fired this tick (or the live decayed timer on a
     * timeout flip).
     *
     * <p>This is the pure contract consumed by {@link #flushAll}; exposing it
     * package-private lets unit tests verify the one-broadcast-per-tick and
     * peak-preservation guarantees without the network stack.</p>
     *
     * @return a map of player UUID to the single outbound state to send; empty
     *         when no player has a pending broadcast
     */
    Map<UUID, PendingOutbound> drainPendingBroadcasts() {
        Map<UUID, PendingOutbound> out = new HashMap<>();
        for (Map.Entry<UUID, ServerShootState> entry : states.entrySet()) {
            ServerShootState state = entry.getValue();
            if (state.pendingBroadcast) {
                float timer = state.pendingAnimTimer > 0f ? state.pendingAnimTimer : state.shootAnimTimer;
                out.put(entry.getKey(), new PendingOutbound(entry.getKey(), state.isFiring, timer));
                state.pendingBroadcast = false;
                state.pendingAnimTimer = 0f;
            }
        }
        return out;
    }

    /**
     * Sends a {@link ShootAnimS2CPacket} to all clients within
     * {@link #BROADCAST_RADIUS} of the player, excluding the shooter.
     *
     * @param player the shooting server player
     * @param packet the state packet to broadcast
     */
    private void send(ServerPlayer player, ShootAnimS2CPacket packet) {
        Vec3 pos = player.position();
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
     * The single outbound animation state scheduled for one player in one
     * tick.
     *
     * @param uuid           the player UUID the packet targets
     * @param isFiring       whether the player is in the firing state
     * @param shootAnimTimer the animation timer to transmit (peak on a shot,
     *                       live value on a timeout flip)
     */
    public record PendingOutbound(UUID uuid, boolean isFiring, float shootAnimTimer) {
    }

    /**
     * Mutable per-player server-side shoot state.
     *
     * <p>Mutated in place every tick by {@link #advanceTick} to avoid per-tick
     * allocation in the server tick loop. Fields are accessed directly by the
     * enclosing service.</p>
     */
    private static final class ServerShootState {
        private boolean isFiring;
        private int ticksSinceLastPacket;
        private float shootAnimTimer;
        /** True when this player needs a single broadcast at tick end. */
        private boolean pendingBroadcast;
        /** Peak timer recorded when a shot fired this tick (0 = none). */
        private float pendingAnimTimer;
    }
}
