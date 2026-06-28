package org.yanbwe.modularshoot.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.event.PostPluginInstallEvent;
import org.yanbwe.modularshoot.plugin.event.PostPluginUninstallEvent;
import org.yanbwe.modularshoot.shooting.FireRateController;
import org.yanbwe.modularshoot.shooting.ModifierVersionAntiCheat;

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
 *       {@link PlayerEvent.PlayerLoggedInEvent} for a full initial sync, and
 *       additionally refreshes the {@code ATTRIBUTE_MODIFIERS} component on
 *       every gun stack in the player's inventory so already-issued guns pick
 *       up definition changes that happened while the player was offline
 *       (设计文档 §惰性刷新路径 K2).</li>
 *   <li><b>Per-gun state modified by hooks</b> &mdash; hooks write state via
 *       {@link org.yanbwe.modularshoot.state.GunState} accessors, which flag
 *       the gun through
 *       {@link org.yanbwe.modularshoot.state.GunSyncThrottleManager#markDirty}.
 *       The throttled flush is handled by
 *       {@link org.yanbwe.modularshoot.state.GunSyncTickHandler} on the next
 *       server tick, subject to a 2-tick throttle interval, so batched state
 *       changes within a single tick produce at most one sync packet. This
 *       service is not involved in that path.</li>
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
 * <p>The static map {@link #previousMainHandGun} is intentionally static
 * because {@link EventBusSubscriber} classes are never instantiated &mdash;
 * all state must live on the class. Entries are cleaned up on
 * {@link PlayerEvent.PlayerLoggedOutEvent} to prevent unbounded growth.
 * Per-gun throttle state lives in
 * {@link org.yanbwe.modularshoot.state.GunSyncThrottleManager}, not here.</p>
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

    private GunSyncService() {
    }

    // ------------------------------------------------------------------
    //  Scenario 3 — player login (full initial sync)
    // ------------------------------------------------------------------

    /**
     * Performs a full gun-data sync when a player logs into the server, and
     * lazily refreshes the {@code ATTRIBUTE_MODIFIERS} component on every gun
     * stack the player already carries.
     *
     * <p>The modifier refresh (设计文档 §惰性刷新路径 K2) ensures that guns
     * issued before a definition change (e.g. a datapack reload, a plugin
     * definition edit, or an attribute-meta default update) automatically pick
     * up the new values on the player's next login, without requiring a
     * re-issue. Both the main inventory and the offhand slot are scanned;
     * armor slots are skipped since a gun cannot be equipped there.</p>
     *
     * <p>After the sync, {@link #previousMainHandGun} is seeded with the
     * player's current main-hand gun uuid so that the first
     * {@link #detectMainHandChange} poll on the next tick does not treat the
     * login-synced gun as a "new switch" and re-sync it redundantly.</p>
     *
     * @param event the login event
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        syncToPlayer(player);
        refreshInventoryGunModifiers(player);
        // Seed the previous-tick tracking so the first PlayerTickEvent.Post
        // does not re-sync the same gun the login handler just synced.
        UUID currentGunUuid = readMainHandGunUuid(player);
        if (currentGunUuid != null) {
            previousMainHandGun.put(player.getUUID(), currentGunUuid);
        }
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
     * by the throttled flush path in
     * {@link org.yanbwe.modularshoot.state.GunSyncTickHandler}, driven by
     * {@link org.yanbwe.modularshoot.state.GunSyncThrottleManager}.</p>
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
    //  Cleanup — remove tracking entries on logout
    // ------------------------------------------------------------------

    /**
     * Removes per-player tracking entries when a player logs out to prevent
     * unbounded growth of the static map.
     *
     * <p>Also cascades cleanup to the fire-rate controller and modifier-version
     * anti-cheat, whose per-player state maps would otherwise retain entries
     * for disconnected players indefinitely (内存泄漏修复).</p>
     *
     * @param event the logout event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerUuid = event.getEntity().getUUID();
        previousMainHandGun.remove(playerUuid);
        FireRateController.clearPlayer(playerUuid);
        ModifierVersionAntiCheat.clearPlayer(playerUuid);
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
     * Refreshes the {@code ATTRIBUTE_MODIFIERS} component on every gun stack
     * in the player's main inventory and offhand slot.
     *
     * <p>Implements the K2 lazy-refresh path (设计文档 §惰性刷新路径): on
     * login, each carried gun stack is reconciled with the current gun/plugin
     * definitions so already-issued guns follow definition changes without a
     * re-issue. The 36-slot main inventory ({@code getInventory().items}) and
     * the offhand slot are scanned; armor slots are skipped since a gun cannot
     * be equipped there. Non-gun stacks are passed over silently.</p>
     *
     * @param player the player whose inventory to refresh; must not be
     *               {@code null}
     */
    private static void refreshInventoryGunModifiers(ServerPlayer player) {
        RegistryAccess registryAccess = player.registryAccess();
        for (ItemStack stack : player.getInventory().items) {
            if (ModularShootAPI.isGun(stack)) {
                AttributeModifierService.refreshModifiers(stack, registryAccess);
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (ModularShootAPI.isGun(offhand)) {
            AttributeModifierService.refreshModifiers(offhand, registryAccess);
        }
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
}
