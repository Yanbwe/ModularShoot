package org.yanbwe.modularshoot.client;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.tooltip.TooltipVersion;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.network.GunStateDiff;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket.PluginSyncEntry;

/**
 * Client-side singleton store for the most recent {@link GunSyncS2CPacket}
 * snapshot of the local player's main-hand gun (设计文档 §GunSyncS2CPacket
 * 客户端用途, lines 2054-2056).
 *
 * <p>Holds the authoritative server-pushed plugin list, modifier version and
 * per-gun state so that client-side consumers — the plugin overlay compositor
 * and the state tooltip builder — can read from a single, dedicated sync
 * channel instead of relying solely on the locally-written {@code GunData}
 * component (which may lag behind or be mutated by client prediction).</p>
 *
 * <p><b>Lifecycle:</b> the store is populated by
 * {@link #handleSync(GunSyncS2CPacket)} (called from the S→C payload handler
 * on the main client thread) and cleared automatically when the local player
 * logs out ({@link ClientPlayerNetworkEvent.LoggingOut}) or switches away
 * from a gun in the main hand ({@link PlayerTickEvent.Pre}). The
 * {@link #hasSyncData()} flag lets callers fall back to local
 * {@code GunData} when no snapshot has been received yet.</p>
 *
 * <p><b>Threading:</b> all access happens on the main client thread —
 * {@code handleSync} via {@code enqueueWork}, event handlers via the event
 * bus — so no explicit synchronization is needed beyond the synchronized
 * {@link #getInstance()} lazy-init (matching
 * {@link org.yanbwe.modularshoot.client.render.BulletRenderManager}).</p>
 *
 * @see GunSyncS2CPacket
 * @see org.yanbwe.modularshoot.client.render.PluginOverlayCompositor
 * @see org.yanbwe.modularshoot.client.tooltip.StateTooltipBuilder
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class ClientGunDataStore {

    private static ClientGunDataStore instance;

    private List<PluginSyncEntry> installedPlugins = List.of();
    private int modifierVersion = 0;
    private CompoundTag state = new CompoundTag();
    private UUID gunInstanceUuid;
    private int hotbarSlot = -1;
    private boolean hasSyncData = false;

    private ClientGunDataStore() {
    }

    /**
     * Returns the singleton store instance, creating it on first call.
     *
     * @return the client-side gun-sync data store
     */
    public static synchronized ClientGunDataStore getInstance() {
        if (instance == null) {
            instance = new ClientGunDataStore();
        }
        return instance;
    }

    /**
     * Stores the authoritative gun-data snapshot pushed by the server.
     *
     * <p>Two modes are supported (阶段 2 / 任务 2.3):</p>
     * <ul>
     *   <li>Full structural sync ({@code statePatch == false}) &mdash; the
     *       plugin list, modifier version and full state map are replaced
     *       wholesale.</li>
     *   <li>State diff ({@code statePatch == true}) &mdash; plugin list and
     *       modifier version are preserved, and the packet's partial state is
     *       {@link GunStateDiff merged} into the stored state (patch keys
     *       overwrite, {@code removedStateKeys} are removed).</li>
     * </ul>
     *
     * <p>Called on the main client thread via {@code enqueueWork} from
     * {@link org.yanbwe.modularshoot.client.ClientPayloadHandlers#handleGunSyncS2C},
     * which only forwards snapshots whose {@code gunInstanceUuid} matches the
     * local main-hand gun (see
     * {@link org.yanbwe.modularshoot.client.ClientGunSyncHandler#isForMainHand}).</p>
     *
     * @param packet the {@link GunSyncS2CPacket} received on the client
     */
    public void handleSync(GunSyncS2CPacket packet) {
        if (packet.statePatch()) {
            // Baseline guard (审查修复): a state patch is a diff against the
            // previously-synced full state. If no full structural sync has been
            // received yet, merging the patch onto an empty state would produce
            // an incomplete baseline. Ignore the patch and wait for the next
            // full structural sync instead.
            if (!hasSyncData) {
                return;
            }
            this.state = GunStateDiff.merge(this.state, packet.state(), packet.removedStateKeys());
            this.gunInstanceUuid = packet.gunInstanceUuid();
            this.hotbarSlot = packet.hotbarSlot();
            return;
        }
        this.installedPlugins = packet.plugins();
        this.modifierVersion = packet.modifierVersion();
        this.state = packet.state();
        this.gunInstanceUuid = packet.gunInstanceUuid();
        this.hotbarSlot = packet.hotbarSlot();
        this.hasSyncData = true;
    }

    /**
     * Returns the installed plugin list from the last sync snapshot.
     *
     * <p>Callers should check {@link #hasSyncData()} first and fall back to
     * local {@code GunData} when no snapshot has been received.</p>
     *
     * @return the plugin list; empty when no sync has been received or the
     *         gun has no plugins
     */
    public List<PluginSyncEntry> getInstalledPlugins() {
        return installedPlugins;
    }

    /**
     * Returns the per-gun state compound tag from the last sync snapshot.
     *
     * <p>Callers should check {@link #hasSyncData()} first and fall back to
     * local {@code GunData} when no snapshot has been received.</p>
     *
     * @return the state compound tag; empty (but non-{@code null}) when no
     *         sync has been received
     */
    public CompoundTag getState() {
        return state;
    }

    /**
     * Returns the anti-cheat modifier version from the last sync snapshot.
     *
     * @return the modifier version; {@code 0} when no sync has been received
     */
    public int getModifierVersion() {
        return modifierVersion;
    }

    /**
     * Returns the {@code gunInstanceUuid} of the gun the current snapshot
     * belongs to.
     *
     * <p>Consumers use this to reject snapshots that no longer match the
     * item being rendered — after a main-hand switch the store may still
     * hold the previous gun's snapshot for a few ticks until the server's
     * new sync arrives (描边污染修复).</p>
     *
     * @return the synced gun's instance uuid, or {@code null} when no sync
     *         has been received
     */
    public @Nullable UUID getGunInstanceUuid() {
        return gunInstanceUuid;
    }

    /**
     * Returns the hotbar slot (0..8) the current snapshot was read from.
     *
     * <p>The slot is the reliable half of the ownership marker: copied gun
     * stacks share their {@code gunInstanceUuid}, so only the slot can tell
     * two copies apart. Consumers accept the snapshot only when this equals
     * the player's current {@code selected} slot (描边污染修复).</p>
     *
     * @return the synced gun's hotbar slot, or {@code -1} when no sync has
     *         been received
     */
    public int getHotbarSlot() {
        return hotbarSlot;
    }

    /**
     * Indicates whether the store has received at least one sync snapshot
     * since the last clear.
     *
     * <p>Consumers use this to decide whether to read from the store or fall
     * back to the local {@code GunData} component.</p>
     *
     * @return {@code true} if a {@link GunSyncS2CPacket} has been processed
     *         and the stored data is valid
     */
    public boolean hasSyncData() {
        return hasSyncData;
    }

    /**
     * Clears all stored sync data, resetting the store to its initial state.
     *
     * <p>Also drops the {@link TooltipVersion} tracked-stream references that
     * key against this store's state payload, so a previous session / gun's
     * payload references are not retained across sessions (阶段 7 / 任务 7.1).</p>
     */
    public void clear() {
        this.installedPlugins = List.of();
        this.modifierVersion = 0;
        this.state = new CompoundTag();
        this.gunInstanceUuid = null;
        this.hotbarSlot = -1;
        this.hasSyncData = false;
        TooltipVersion.clear();
    }

    /**
     * Clears the store when the local player logs out (disconnect / world
     * close) so stale sync data never leaks across sessions.
     *
     * @param event the logging-out event
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        getInstance().clear();
    }

    /**
     * Clears the store when the local player is no longer holding the
     * snapshot's gun in the main hand, so the compositor and tooltip do not
     * render stale overlays for a different gun.
     *
     * <p>Identity is checked via the hotbar slot <em>and</em>
     * {@code gunInstanceUuid}: the slot catches hotbar switches between
     * copied guns that share the same uuid (the uuid comparison alone would
     * miss those), the uuid catches replacing the item in the same slot
     * (描边污染修复). Only the local player's main hand is checked; remote
     * players are ignored via an {@code instanceof LocalPlayer} guard. The
     * check is skipped entirely when the store is already empty
     * ({@link #hasSyncData()} is {@code false}) to avoid per-tick work.</p>
     *
     * @param event the pre-tick event carrying the ticking player
     */
    @SubscribeEvent
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LocalPlayer player)) {
            return;
        }
        ClientGunDataStore store = getInstance();
        if (!store.hasSyncData()) {
            return;
        }
        ItemStack mainHand = player.getMainHandItem();
        GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        boolean slotMatch = store.getHotbarSlot() == player.getInventory().selected;
        if (gunData == null
                || !slotMatch
                || !Objects.equals(gunData.gunInstanceUuid(), store.getGunInstanceUuid())) {
            store.clear();
        }
    }
}
