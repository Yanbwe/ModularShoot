package org.yanbwe.modularshoot.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.network.PlayerStateSyncService;

/**
 * Server-side tick handler that flushes throttled per-player state syncs
 * (任务 3.3 — PlayerState 同步节流, mirroring {@link GunSyncTickHandler}).
 *
 * <p>Listens to {@link LevelTickEvent.Post} on the game bus. After each server
 * level finishes its tick work, the handler iterates every online player in
 * that level and asks {@link PlayerStateThrottleManager#shouldSync} whether a
 * throttled sync is due. When it is, a
 * {@link org.yanbwe.modularshoot.network.PlayerStateS2CPacket} is dispatched to
 * the player via {@link PlayerStateSyncService#syncStateToPlayer} and the
 * player is marked as synced.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * handler runs exclusively on the authoritative server, matching the
 * NeoForge-recommended pattern for {@code LevelTickEvent} handlers.</p>
 *
 * <h2>Relationship with {@link PlayerStateSyncService}</h2>
 * <p>Player login is a <em>critical moment</em> handled by
 * {@link PlayerStateSyncService} directly (immediate, unbounded sync). This
 * handler covers only the throttled flush of per-player state modified by
 * {@link PlayerState} write accessors.</p>
 *
 * <h2>Periodic cleanup</h2>
 * <p>Every {@link #CLEANUP_INTERVAL_TICKS} ticks the handler collects the uuid
 * of every online player and calls {@link PlayerStateThrottleManager#cleanup},
 * evicting stale entries and preventing memory leaks from disconnected
 * players.</p>
 *
 * @see PlayerStateThrottleManager
 * @see PlayerStateSyncService
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class PlayerStateSyncTickHandler {

    /**
     * Interval between throttle-state cleanup sweeps, in ticks. 100 ticks
     * &asymp; 5 seconds &mdash; frequent enough to bound map growth, rare
     * enough to make the full-server player scan negligible.
     */
    private static final long CLEANUP_INTERVAL_TICKS = 100L;

    private PlayerStateSyncTickHandler() {
    }

    /**
     * Fired once per tick per level after the level has finished its work.
     *
     * <p>On the authoritative server, checks each online player's per-player
     * state and sends a throttled sync when it is dirty and the throttle
     * interval has elapsed. Periodically also runs a cleanup sweep to evict
     * stale throttle entries.</p>
     *
     * @param event the post-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        long currentTick = level.getGameTime();
        flushDirtyPlayers(level, currentTick);
        maybeCleanup(level, currentTick);
    }

    /**
     * Iterates all players in the level and sends a throttled sync for each
     * player whose per-player state is dirty and past the throttle interval.
     *
     * @param level       the ticking server level
     * @param currentTick the current game time
     */
    private static void flushDirtyPlayers(Level level, long currentTick) {
        PlayerStateThrottleManager manager = PlayerStateThrottleManager.getInstance();
        for (Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            UUID playerUuid = serverPlayer.getUUID();
            if (!manager.shouldSync(playerUuid, currentTick)) {
                continue;
            }
            PlayerStateSyncService.syncStateToPlayer(serverPlayer);
            manager.markSynced(playerUuid, currentTick);
        }
    }

    /**
     * Periodically removes throttle entries for players no longer online.
     *
     * <p>Collects active player uuids across <em>all</em> dimensions via the
     * server's player list so that players in other levels are not
     * erroneously evicted.</p>
     *
     * @param level       the ticking server level (used to obtain the server)
     * @param currentTick the current game time
     */
    private static void maybeCleanup(Level level, long currentTick) {
        if (currentTick % CLEANUP_INTERVAL_TICKS != 0L) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        Set<UUID> activePlayerUuids = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            activePlayerUuids.add(player.getUUID());
        }
        PlayerStateThrottleManager.getInstance().cleanup(activePlayerUuids);
    }
}
