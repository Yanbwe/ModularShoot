package org.yanbwe.modularshoot.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.network.GunSyncService;

/**
 * Server-side tick handler that flushes throttled per-gun state syncs
 * (设计文档 §同步节流策略, lines 1843-1851, and §触发时机, lines 2043-2048).
 *
 * <p>Listens to {@link LevelTickEvent.Post} on the game bus. After each server
 * level finishes its tick work, the handler iterates every online player in
 * that level, inspects the main-hand gun, and asks
 * {@link GunSyncThrottleManager#shouldSync} whether a throttled sync is due.
 * When it is, a {@link org.yanbwe.modularshoot.network.GunSyncS2CPacket} is
 * dispatched to the player via {@link GunSyncService#syncToPlayer} and the
 * gun is marked as synced.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * handler runs exclusively on the authoritative server, matching the
 * NeoForge-recommended pattern for {@code LevelTickEvent} handlers used by
 * {@link org.yanbwe.modularshoot.network.BulletSyncService} and
 * {@link GunSyncService}.</p>
 *
 * <h2>Relationship with {@link GunSyncService}</h2>
 * <p>The three <em>critical-moment</em> trigger scenarios &mdash; main-hand
 * switch, plugin install/uninstall, and player login &mdash; are handled by
 * {@link GunSyncService} and sync <strong>immediately</strong>, bypassing the
 * throttle entirely. This handler covers only the fourth scenario: per-gun
 * state modified by hooks, flushed at the throttled cadence.</p>
 *
 * <h2>Periodic cleanup</h2>
 * <p>Every {@link #CLEANUP_INTERVAL_TICKS} ticks the handler collects the
 * {@code gunInstanceUuid} of every online player's main-hand gun and calls
 * {@link GunSyncThrottleManager#cleanup}, evicting stale entries and
 * preventing memory leaks from guns that are no longer in any player's main
 * hand.</p>
 *
 * @see GunSyncThrottleManager
 * @see GunSyncService
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class GunSyncTickHandler {

    /**
     * Interval between throttle-state cleanup sweeps, in ticks. 100 ticks
     * &asymp; 5 seconds &mdash; frequent enough to bound map growth, rare
     * enough to make the full-server player scan negligible.
     */
    private static final long CLEANUP_INTERVAL_TICKS = 100L;

    private GunSyncTickHandler() {
    }

    /**
     * Fired once per tick per level after the level has finished its work.
     *
     * <p>On the authoritative server, checks each online player's main-hand
     * gun and sends a throttled sync when the gun's state is dirty and the
     * throttle interval has elapsed. Periodically also runs a cleanup sweep
     * to evict stale throttle entries.</p>
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
        flushDirtyGuns(level, currentTick);
        maybeCleanup(level, currentTick);
    }

    /**
     * Iterates all players in the level and sends a throttled sync for each
     * player whose main-hand gun is dirty and past the throttle interval.
     *
     * @param level       the ticking server level
     * @param currentTick the current game time
     */
    private static void flushDirtyGuns(Level level, long currentTick) {
        GunSyncThrottleManager manager = GunSyncThrottleManager.getInstance();
        for (Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            syncPlayerGunIfReady(serverPlayer, manager, currentTick);
        }
    }

    /**
     * Checks the player's main-hand gun against the throttle manager and, if
     * a throttled sync is due, dispatches a {@link GunSyncS2CPacket} and
     * stamps the gun as synced.
     *
     * @param player      the server player whose main-hand gun to check
     * @param manager     the throttle manager singleton
     * @param currentTick the current game time
     */
    private static void syncPlayerGunIfReady(
            ServerPlayer player, GunSyncThrottleManager manager, long currentTick) {
        UUID gunUuid = readMainHandGunUuid(player);
        if (gunUuid == null) {
            return;
        }
        if (!manager.shouldSync(gunUuid, currentTick)) {
            return;
        }
        // Reuse GunSyncService's packet builder + sender to stay DRY.
        GunSyncService.syncToPlayer(player);
        manager.markSynced(gunUuid, currentTick);
    }

    /**
     * Periodically removes throttle entries for guns no longer in any online
     * player's main hand, preventing unbounded map growth.
     *
     * <p>Collects active gun uuids across <em>all</em> dimensions via the
     * server's player list so that guns held by players in other levels are
     * not erroneously evicted.</p>
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
        Set<UUID> activeGunUuids = collectActiveGunUuids(server);
        GunSyncThrottleManager.getInstance().cleanup(activeGunUuids);
    }

    /**
     * Collects the {@code gunInstanceUuid} of every online player's main-hand
     * gun across all dimensions.
     *
     * @param server the Minecraft server
     * @return a mutable set of active main-hand gun uuids (possibly empty)
     */
    private static Set<UUID> collectActiveGunUuids(MinecraftServer server) {
        Set<UUID> activeGunUuids = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = readMainHandGunUuid(player);
            if (uuid != null) {
                activeGunUuids.add(uuid);
            }
        }
        return activeGunUuids;
    }

    /**
     * Reads the {@code gunInstanceUuid} of a player's main-hand gun, or
     * {@code null} when the main-hand item is not a framework gun or carries
     * no {@code gun_data} component.
     *
     * @param player the player whose main hand to inspect
     * @return the main-hand gun's instance uuid, or {@code null}
     */
    private static @Nullable UUID readMainHandGunUuid(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return null;
        }
        GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunInstanceUuid();
    }
}
