package org.yanbwe.modularshoot.client;

import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.network.GunStateDiff;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;

/**
 * Client-side handler for {@link GunSyncS2CPacket} (设计文档 §GunSyncS2CPacket
 * 客户端用途, lines 2054-2057).
 *
 * <p>When the server pushes an authoritative gun-data snapshot, this handler
 * rebuilds the local player's main-hand {@link GunData} from the packet's
 * plugin list, {@code modifierVersion} and per-gun {@code state} map, then
 * writes it back onto the client's item stack. This keeps the client's gun
 * model, plugin visual overlays, and HUD state text aligned with the server
 * (设计文档 §持久化与同步, line 1835).</p>
 *
 * <p><b>Preserved fields:</b> the packet does not carry {@code gunId} or
 * {@code gunInstanceUuid} &mdash; they are immutable for a given stack and
 * already present on the client &mdash; so the handler copies them from the
 * existing client-side {@link GunData}. Only the mutable fields (installed
 * plugins, {@code modifierVersion} and {@code state}) are overwritten from
 * the packet.</p>
 *
 * <p><b>Client-only.</b> Referenced solely from the S→C payload handler
 * in {@link org.yanbwe.modularshoot.client.ClientPayloadHandlers}, which is
 * invoked only on the physical client. The class is never loaded on a
 * dedicated server, matching the pattern of
 * {@link org.yanbwe.modularshoot.client.ClientGunDataStore} and the other
 * client handler classes ({@code ClientHitEffectHandler},
 * {@code PlayerShootStateManager}).</p>
 *
 * @see GunSyncS2CPacket
 * @see GunData
 */
public final class ClientGunSyncHandler {

    private ClientGunSyncHandler() {
    }

    /**
     * Applies an incoming {@link GunSyncS2CPacket} to the local player's
     * main-hand gun.
     *
     * <p>Silently does nothing when {@link #isForMainHand} rejects the
     * packet (client player absent, main-hand item not a framework gun, no
     * {@code gun_data} component, or the snapshot's {@code gunInstanceUuid}
     * no longer matches the current main-hand gun) — matching the defensive
     * null-guard pattern in
     * {@link org.yanbwe.modularshoot.network.GunSyncService#syncToPlayer}.</p>
     *
     * @param packet the authoritative gun-data snapshot from the server
     */
    public static void handlePacket(GunSyncS2CPacket packet) {
        if (!isForMainHand(packet)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ItemStack mainHand = player.getMainHandItem();
        @Nullable GunData existing = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        GunData synced = rebuildGunData(existing, packet);
        mainHand.set(ModularShootDataComponents.GUN_DATA.get(), synced);
    }

    /**
     * Whether the incoming snapshot is authoritative for the local player's
     * current main-hand gun.
     *
     * <p>The ownership check has two halves: the packet's {@code hotbarSlot}
     * must equal the player's current {@code selected} slot (copied gun
     * stacks share their {@code gunInstanceUuid}, so the slot is the only
     * reliable discriminator between them), and — when the slot matches —
     * the packet's {@code gunInstanceUuid} must equal the main-hand stack's
     * own (catches replacing the item in the same slot). A snapshot that
     * fails either check describes a gun the player has switched away from
     * (network ordering / stale sync) and must be dropped wholesale,
     * otherwise the new gun's component data, the overlay compositor and the
     * {@link ClientGunDataStore} would all be contaminated with another
     * gun's plugins and outlines (描边污染修复).</p>
     *
     * @param packet the incoming {@link GunSyncS2CPacket}
     * @return {@code true} when the packet matches the current main-hand gun
     */
    public static boolean isForMainHand(GunSyncS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        if (packet.hotbarSlot() != player.getInventory().selected) {
            return false;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand, player.registryAccess())) {
            return false;
        }
        @Nullable GunData existing = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        return existing != null && Objects.equals(existing.gunInstanceUuid(), packet.gunInstanceUuid());
    }

    /**
     * Reconstructs a {@link GunData} from the packet, preserving the immutable
     * {@code gunId} and {@code gunInstanceUuid} from the existing client data.
     *
     * <p>Two modes are supported (阶段 2 / 任务 2.3):</p>
     * <ul>
     *   <li>Full structural sync ({@code statePatch == false}) &mdash; the
     *       mutable plugin list, modifier version and full state map are
     *       overwritten from the server snapshot.</li>
     *   <li>State diff ({@code statePatch == true}) &mdash; the plugin list and
     *       modifier version are unchanged, and the packet's partial state is
     *       {@link GunStateDiff merged} into the existing state map (patch keys
     *       overwrite, {@code removedStateKeys} are removed).</li>
     * </ul>
     *
     * @param existing the current client-side gun data (source of gunId/uuid)
     * @param packet   the server packet (full snapshot or state diff)
     * @return a new immutable {@link GunData} ready to write onto the stack
     */
    static GunData rebuildGunData(GunData existing, GunSyncS2CPacket packet) {
        if (packet.statePatch()) {
            // Baseline guard (审查修复): a state patch is a diff against the
            // previously-synced full structural state. If no full sync has been
            // received yet, the local GunData state may not be an authoritative
            // baseline, so merging the partial diff onto it would produce an
            // incomplete / drifted state. Ignore the patch and wait for the next
            // full structural sync (equivalent baseline guard to
            // ClientGunDataStore.handleSync).
            if (!ClientGunDataStore.getInstance().hasSyncData()) {
                return existing;
            }
            List<PluginInstance> plugins = existing.installedPlugins();
            CompoundTag mergedState = GunStateDiff.merge(
                    existing.state(), packet.state(), packet.removedStateKeys());
            return new GunData(
                    existing.gunId(),
                    existing.gunInstanceUuid(),
                    plugins,
                    existing.modifierVersion(),
                    mergedState);
        }
        List<PluginInstance> plugins = packet.plugins().stream()
                .map(ClientGunSyncHandler::toPluginInstance)
                .toList();
        return new GunData(
                existing.gunId(),
                existing.gunInstanceUuid(),
                plugins,
                packet.modifierVersion(),
                packet.state()
        );
    }

    /**
     * Converts a single {@link GunSyncS2CPacket.PluginSyncEntry} back into a
     * {@link PluginInstance}. The two records mirror the same four fields
     * (pluginId, instanceUuid, installedTypeId, locked).
     *
     * @param entry the wire-format plugin entry
     * @return the corresponding {@link PluginInstance}
     */
    private static PluginInstance toPluginInstance(GunSyncS2CPacket.PluginSyncEntry entry) {
        return new PluginInstance(
                entry.pluginId(),
                entry.instanceUuid(),
                entry.installedTypeId(),
                entry.locked()
        );
    }
}
