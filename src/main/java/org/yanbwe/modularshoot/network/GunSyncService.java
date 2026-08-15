package org.yanbwe.modularshoot.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
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
     * Tracks each player's previous-tick main-hand selection: the hotbar slot
     * and the {@code gunInstanceUuid} of the gun in it, keyed by player uuid.
     * Absent means non-gun (matching {@code ConcurrentHashMap}'s
     * no-null-value contract).
     *
     * <p>The slot is tracked in addition to the uuid because copied gun
     * stacks share the same {@code gunInstanceUuid} — switching between two
     * copies would otherwise be invisible to the uuid comparison and no sync
     * would fire (描边污染修复).</p>
     */
    private record MainHandTrack(int hotbarSlot, UUID gunUuid) {
    }

    private static final Map<UUID, MainHandTrack> previousMainHand = new ConcurrentHashMap<>();

    /**
     * Per-(player, gun) last-synced state, used to build the minimal state
     * diff on the throttled state-flush path (阶段 2 / 任务 2.3 §GunSync 状态
     * diff). Keyed by a stable pair so a structural full sync for a new gun
     * never diffs against the previous gun's state. Cleaned up on logout.
     */
    private record PlayerStateKey(UUID playerUuid, UUID gunUuid) {
    }

    private static final Map<PlayerStateKey, CompoundTag> LAST_SYNCED_STATE =
            new ConcurrentHashMap<>();

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
     * <p>After the sync, {@link #previousMainHand} is seeded with the
     * player's current main-hand slot and gun uuid so that the first
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
            previousMainHand.put(player.getUUID(),
                    new MainHandTrack(player.getInventory().selected, currentGunUuid));
        }
    }

    // ------------------------------------------------------------------
    //  Scenario 1 — main-hand item switch detection (per-tick polling)
    // ------------------------------------------------------------------

    /**
     * Polled every tick after each player updates; detects main-hand gun
     * switches by comparing the current hotbar slot and gun instance uuid
     * against the previous tick's values.
     *
     * <p>Either change fires a sync: a slot change covers ordinary hotbar
     * scrolling (including switches between copied guns that share the same
     * {@code gunInstanceUuid}, which a uuid-only comparison would miss), and
     * a uuid change covers replacing the item in the same slot. Same-gun
     * state mutations are ignored here to avoid a packet flood while
     * shooting; those are handled by the throttled flush path in
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
        previousMainHand.remove(playerUuid);
        LAST_SYNCED_STATE.entrySet().removeIf(e -> e.getKey().playerUuid().equals(playerUuid));
        FireRateController.clearPlayer(playerUuid);
        ModifierVersionAntiCheat.clearPlayer(playerUuid);
    }

    // ------------------------------------------------------------------
    //  Core sync logic
    // ------------------------------------------------------------------

    /**
     * Builds and sends a full structural {@link GunSyncS2CPacket} from the
     * player's main-hand gun data (插件列表 + modifierVersion + 完整 state) and
     * records the synced state for subsequent diffs.
     *
     * <p>Used by the structural trigger scenarios: player login, main-hand
     * switch, and plugin install/uninstall. The client replaces its copy
     * wholesale (no merge).</p>
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
        if (!ModularShootAPI.isGun(mainHand, player.registryAccess())) {
            return;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        GunSyncS2CPacket packet = buildFullPacket(gunData, player.getInventory().selected);
        PacketDistributor.sendToPlayer(player, packet);
        recordSyncedState(player, gunData);
    }

    /**
     * Builds and sends a minimal {@link GunStateDiff} state-patch
     * {@link GunSyncS2CPacket} for the player's main-hand gun, sending only
     * the state keys that changed since the last sync (阶段 2 / 任务 2.3).
     *
     * <p>Used by the throttled per-gun state flush path
     * ({@link org.yanbwe.modularshoot.state.GunSyncTickHandler}), where the
     * plugin list and modifier version are unchanged. If nothing changed,
     * no packet is sent. On the first call for a gun (no recorded base state)
     * it falls back to a full structural sync so the client always has a
     * complete baseline before receiving patches.</p>
     *
     * @param player the player whose main-hand gun state may have changed
     */
    public static void syncStateToPlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand, player.registryAccess())) {
            return;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        PlayerStateKey key = new PlayerStateKey(player.getUUID(), gunData.gunInstanceUuid());
        int hotbarSlot = player.getInventory().selected;
        CompoundTag current = gunData.state();
        CompoundTag previous = LAST_SYNCED_STATE.get(key);
        if (previous == null) {
            // No baseline yet — send a full structural sync so the client has
            // the complete state before any subsequent patch.
            PacketDistributor.sendToPlayer(player, buildFullPacket(gunData, hotbarSlot));
            recordSyncedState(player, gunData);
            return;
        }
        CompoundTag patch = GunStateDiff.diff(previous, current);
        List<String> removed = GunStateDiff.removedKeys(previous, current);
        if (patch.isEmpty() && removed.isEmpty()) {
            // No state change — nothing to send. Keep the recorded base so the
            // next change still diffs correctly.
            return;
        }
        GunSyncS2CPacket packet = GunSyncS2CPacket.statePatch(
                gunData.gunInstanceUuid(), hotbarSlot, patch, removed);
        PacketDistributor.sendToPlayer(player, packet);
        LAST_SYNCED_STATE.put(key, current.copy());
    }

    /**
     * Records the current gun state as the last-synced baseline for a player's
     * gun, so a later {@link #syncStateToPlayer} diffs against it.
     *
     * <p><b>Defensive copy (审查修复).</b> The baseline is a {@code copy()} of
     * the gun's live state rather than a reference, so in-place server-side
     * mutations of the gun's {@code GunData.state()} between syncs cannot
     * corrupt the stored diff base.</p>
     *
     * @param player  the player
     * @param gunData the gun whose state is now fully in sync
     */
    private static void recordSyncedState(ServerPlayer player, GunData gunData) {
        LAST_SYNCED_STATE.put(
                new PlayerStateKey(player.getUUID(), gunData.gunInstanceUuid()),
                gunData.state().copy());
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
            if (ModularShootAPI.isGun(stack, registryAccess)) {
                AttributeModifierService.refreshModifiers(stack, registryAccess);
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (ModularShootAPI.isGun(offhand, registryAccess)) {
            AttributeModifierService.refreshModifiers(offhand, registryAccess);
        }
    }

    /**
     * Maps a {@link GunData} into a full structural {@link GunSyncS2CPacket}
     * by projecting each {@link PluginInstance} onto a
     * {@link GunSyncS2CPacket.PluginSyncEntry}.
     *
     * @param gunData the source gun data
     * @return a new full {@link GunSyncS2CPacket} ready to send
     */
    private static GunSyncS2CPacket buildFullPacket(GunData gunData, int hotbarSlot) {
        List<GunSyncS2CPacket.PluginSyncEntry> entries = gunData.installedPlugins().stream()
                .map(GunSyncService::toSyncEntry)
                .toList();
        return GunSyncS2CPacket.full(gunData.gunInstanceUuid(), hotbarSlot, entries,
                gunData.modifierVersion(), gunData.state());
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
     * Compares the current main-hand hotbar slot and gun instance uuid
     * against the previous tick's values and triggers a sync when a genuine
     * switch is detected.
     *
     * <p>Updates {@link #previousMainHand} regardless of whether a sync
     * fires, so the map always reflects the latest main-hand state.</p>
     *
     * @param player the ticking server player
     */
    private static void detectMainHandChange(ServerPlayer player) {
        UUID playerUuid = player.getUUID();
        int selected = player.getInventory().selected;
        UUID currentGunUuid = readMainHandGunUuid(player);
        MainHandTrack previous = previousMainHand.get(playerUuid);
        if (previous == null
                || previous.hotbarSlot() != selected
                || !Objects.equals(previous.gunUuid(), currentGunUuid)) {
            if (currentGunUuid != null) {
                previousMainHand.put(playerUuid, new MainHandTrack(selected, currentGunUuid));
                syncToPlayer(player);
            } else {
                // Switching away from a gun (or to a non-gun item).
                // ConcurrentHashMap does not allow null values, so remove
                // the entry rather than putting null.
                previousMainHand.remove(playerUuid);
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
        if (!ModularShootAPI.isGun(mainHand, player.registryAccess())) {
            return null;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunInstanceUuid();
    }
}
