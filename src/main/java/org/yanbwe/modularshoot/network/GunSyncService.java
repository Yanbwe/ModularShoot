package org.yanbwe.modularshoot.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.event.PostPluginInstallEvent;
import org.yanbwe.modularshoot.plugin.event.PostPluginUninstallEvent;

/**
 * Server-side gun-data sync orchestrator (设计文档 §GunSyncS2CPacket 触发时机,
 * lines 2043-2047).
 *
 * <p>Monitors four trigger scenarios and, when the player's main-hand item is a
 * framework gun, builds a {@link GunSyncS2CPacket} from the gun's
 * {@link GunData} and broadcasts it to the owning client via
 * {@link PacketDistributor#sendToPlayer}. This keeps the client's gun model,
 * plugin visual overlays, and HUD state text aligned with the authoritative
 * server state.</p>
 *
 * <h2>Trigger scenarios</h2>
 * <ol>
 *   <li><b>Main-hand item switch</b> &mdash; detected per-tick via
 *       {@link PlayerTickEvent.Post} by comparing the current main-hand gun's
 *       {@code gunInstanceUuid} against the previous tick's value. Only a
 *       genuine gun-instance change triggers a sync, so in-flight state changes
 *       (e.g. ammo decrement while shooting) do not cause a flood of packets;
 *       those are covered by scenario 4.</li>
 *   <li><b>Plugin install / uninstall</b> &mdash; listens to
 *       {@link PostPluginInstallEvent} and {@link PostPluginUninstallEvent} on
 *       the game bus and syncs immediately so the client sees the new plugin
 *       layout without waiting for the next tick.</li>
 *   <li><b>Player login</b> &mdash; listens to
 *       {@link PlayerEvent.PlayerLoggedInEvent} for a full initial sync.</li>
 *   <li><b>Per-gun state modified by hooks</b> &mdash; hooks call
 *       {@link #markStateDirty(ServerPlayer)} to flag the player; the flag is
 *       flushed on the next {@link LevelTickEvent.Post} so batched state
 *       changes within a single tick produce at most one sync packet.</li>
 * </ol>
 *
 * <p><b>Server-only.</b> Every handler guards against
 * {@code level().isClientSide()} so the authoritative server is the sole
 * sender, matching the NeoForge-recommended pattern for tick and player
 * events. The class is registered on the game bus via
 * {@link EventBusSubscriber} with no {@code bus} parameter, consistent with
 * {@link org.yanbwe.modularshoot.event.OffhandRestrictionHandler} and
 * {@link org.yanbwe.modularshoot.bullet.BulletTickHandler}.</p>
 *
 * <h2>State tracking</h2>
 * <p>The two static maps ({@link #previousMainHandGun} and
 * {@link #dirtyPlayers}) are intentionally static because
 * {@link EventBusSubscriber} classes are never instantiated &mdash; all state
 * must live on the class. Entries are cleaned up on
 * {@link PlayerEvent.PlayerLoggedOutEvent} to prevent unbounded growth.</p>
 *
 * @see GunSyncS2CPacket
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class GunSyncService {

    /**
     * Tracks the {@code gunInstanceUuid} of each player's previous-tick
     * main-hand gun, keyed by player uuid. When a player is not holding a
     * (valid) gun, the entry is removed from this map (absent means non-gun,
     * matching {@code ConcurrentHashMap}'s no-null-value contract).
     */
    private static final Map<UUID, UUID> previousMainHandGun = new ConcurrentHashMap<>();

    /**
     * Set of player uuids flagged for a state-dirty sync. Flushed and cleared
     * every tick in {@link #onLevelTickPost(LevelTickEvent.Post)}.
     */
    private static final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();

    private GunSyncService() {
    }

    // ------------------------------------------------------------------
    //  Scenario 3 — player login (full initial sync)
    // ------------------------------------------------------------------

    /**
     * Performs a full gun-data sync when a player logs into the server.
     *
     * @param event the login event
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        syncToPlayer(player);
    }

    // ------------------------------------------------------------------
    //  Scenario 1 — main-hand item switch detection (per-tick polling)
    // ------------------------------------------------------------------

    /**
     * Polled every tick after each player updates; detects main-hand gun
     * switches by comparing the current gun instance uuid against the previous
     * tick's value.
     *
     * <p>Only a genuine gun-instance change (different uuid, or gun &harr;
     * non-gun transition) triggers a sync. Same-gun state mutations are
     * ignored here to avoid a packet flood while shooting; those are handled
     * by scenario 4 ({@link #markStateDirty}).</p>
     *
     * @param event the post-tick event carrying the ticking player
     */
    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        detectMainHandChange(player);
    }

    // ------------------------------------------------------------------
    //  Scenario 2 — plugin install / uninstall
    // ------------------------------------------------------------------

    /**
     * Syncs gun data immediately after a plugin is installed so the client
     * sees the new plugin layout without waiting for the next tick.
     *
     * @param event the post-install event
     */
    @SubscribeEvent
    public static void onPostPluginInstall(PostPluginInstallEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        syncToPlayer(player);
    }

    /**
     * Syncs gun data immediately after a plugin is uninstalled.
     *
     * <p>The player may be {@code null} when the uninstall was triggered by a
     * non-player source; in that case no sync is performed (there is no client
     * to sync to).</p>
     *
     * @param event the post-uninstall event
     */
    @SubscribeEvent
    public static void onPostPluginUninstall(PostPluginUninstallEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        syncToPlayer(player);
    }

    // ------------------------------------------------------------------
    //  Scenario 4 — per-gun state dirty flush (next-tick batched)
    // ------------------------------------------------------------------

    /**
     * Flags a player for a gun-data sync on the next
     * {@link LevelTickEvent.Post}. Called by state-modifying hooks so that
     * multiple state writes within the same tick coalesce into a single
     * outbound packet.
     *
     * @param player the player whose main-hand gun state changed; must not be
     *               {@code null}
     */
    public static void markStateDirty(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        dirtyPlayers.add(player.getUUID());
    }

    /**
     * Flushes pending state-dirty syncs for every player in the ticking level.
     *
     * <p>Fires once per tick per level on both logical sides; guarded to run
     * only on the authoritative server. Players flagged via
     * {@link #markStateDirty} are synced and unflagged here.</p>
     *
     * @param event the post-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        flushDirtyPlayers(event.getLevel());
    }

    // ------------------------------------------------------------------
    //  Cleanup — remove tracking entries on logout
    // ------------------------------------------------------------------

    /**
     * Removes per-player tracking entries when a player logs out to prevent
     * unbounded growth of the static maps.
     *
     * @param event the logout event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.getEntity().getUUID();
        previousMainHandGun.remove(playerUuid);
        dirtyPlayers.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    //  Core sync logic
    // ------------------------------------------------------------------

    /**
     * Builds a {@link GunSyncS2CPacket} from the player's main-hand gun data
     * and sends it to the player's client.
     *
     * <p>Silently does nothing when the main-hand item is not a framework gun
     * or carries no {@code gun_data} component &mdash; a gun stack should
     * always have {@code gun_data}, but the null guard defends against
     * malformed (e.g. command-spawned) stacks without throwing, matching the
     * pattern in {@link org.yanbwe.modularshoot.client.ClientShootSender#sendShootRequest}.</p>
     *
     * @param player the player to sync to; must not be {@code null}
     */
    public static void syncToPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        GunSyncS2CPacket packet = buildPacket(gunData);
        PacketDistributor.sendToPlayer(player, packet);
    }

    /**
     * Maps a {@link GunData} into a {@link GunSyncS2CPacket} by projecting
     * each {@link PluginInstance} onto a {@link GunSyncS2CPacket.PluginSyncEntry}.
     *
     * @param gunData the source gun data
     * @return a new {@link GunSyncS2CPacket} ready to send
     */
    private static GunSyncS2CPacket buildPacket(GunData gunData) {
        List<GunSyncS2CPacket.PluginSyncEntry> entries = gunData.installedPlugins().stream()
                .map(GunSyncService::toSyncEntry)
                .toList();
        return new GunSyncS2CPacket(entries, gunData.modifierVersion(), gunData.state());
    }

    /**
     * Converts a single {@link PluginInstance} into a
     * {@link GunSyncS2CPacket.PluginSyncEntry}.
     *
     * @param plugin the plugin instance to convert
     * @return the corresponding sync entry
     */
    private static GunSyncS2CPacket.PluginSyncEntry toSyncEntry(PluginInstance plugin) {
        return new GunSyncS2CPacket.PluginSyncEntry(
                plugin.pluginId(),
                plugin.instanceUuid(),
                plugin.installedTypeId(),
                plugin.locked()
        );
    }

    // ------------------------------------------------------------------
    //  Main-hand change detection
    // ------------------------------------------------------------------

    /**
     * Compares the current main-hand gun instance uuid against the previous
     * tick's value and triggers a sync when a genuine switch is detected.
     *
     * <p>Updates {@link #previousMainHandGun} regardless of whether a sync
     * fires, so the map always reflects the latest main-hand state.</p>
     *
     * @param player the ticking server player
     */
    private static void detectMainHandChange(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        UUID currentGunUuid = readMainHandGunUuid(player);
        UUID previousGunUuid = previousMainHandGun.get(playerUuid);
        if (!Objects.equals(currentGunUuid, previousGunUuid)) {
            if (currentGunUuid != null) {
                previousMainHandGun.put(playerUuid, currentGunUuid);
                syncToPlayer(player);
            } else {
                // Switching away from a gun (or to a non-gun item).
                // ConcurrentHashMap does not allow null values, so remove
                // the entry rather than putting null.
                previousMainHandGun.remove(playerUuid);
            }
        }
    }

    /**
     * Reads the {@code gunInstanceUuid} of the player's main-hand gun, or
     * {@code null} when the main-hand item is not a gun or carries no
     * {@code gun_data} component.
     *
     * @param player the player whose main hand to inspect
     * @return the main-hand gun's instance uuid, or {@code null}
     */
    private static @Nullable UUID readMainHandGunUuid(ServerPlayer player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return null;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunInstanceUuid();
    }

    /**
     * Flushes the state-dirty flag for every flagged player currently in the
     * given level, sending a sync packet and clearing the flag.
     *
     * <p>Iterates the level's player list and checks membership in
     * {@link #dirtyPlayers}. This is O(players-in-level) per tick, which is
     * negligible for typical server populations.</p>
     *
     * @param level the server level whose players to flush
     */
    private static void flushDirtyPlayers(Level level) {
        if (dirtyPlayers.isEmpty()) {
            return;
        }
        for (Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                continue;
            }
            if (dirtyPlayers.remove(serverPlayer.getUUID())) {
                syncToPlayer(serverPlayer);
            }
        }
    }
}
