package org.yanbwe.modularshoot.client;

import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
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
     * <p>Overwrites any previously stored data. Called on the main client
     * thread via {@code enqueueWork} from
     * {@link org.yanbwe.modularshoot.network.ModularShootPayloads#handleGunSyncS2C}.</p>
     *
     * @param packet the {@link GunSyncS2CPacket} received on the client
     */
    public void handleSync(GunSyncS2CPacket packet) {
        this.installedPlugins = packet.plugins();
        this.modifierVersion = packet.modifierVersion();
        this.state = packet.state();
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
     */
    public void clear() {
        this.installedPlugins = List.of();
        this.modifierVersion = 0;
        this.state = new CompoundTag();
        this.hasSyncData = false;
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
     * Clears the store when the local player is no longer holding a gun in
     * the main hand, so the compositor and tooltip do not render stale
     * overlays for a non-gun item.
     *
     * <p>Only the local player's main hand is checked; remote players are
     * ignored via an {@code instanceof LocalPlayer} guard. The check is
     * skipped entirely when the store is already empty
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
        if (!ModularShootAPI.isGun(mainHand)) {
            store.clear();
        }
    }
}
