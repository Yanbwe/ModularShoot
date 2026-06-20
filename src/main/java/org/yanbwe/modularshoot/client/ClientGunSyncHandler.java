package org.yanbwe.modularshoot.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
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
 * <p><b>Client-only.</b> Referenced solely from the S→C payload handler lambda
 * in {@link org.yanbwe.modularshoot.network.ModularShootPayloads}, which is
 * invoked only on the physical client. The class is never loaded on a
 * dedicated server, matching the pattern of
 * {@link org.yanbwe.modularshoot.client.render.PluginOverlayCompositor} and
 * {@link org.yanbwe.modularshoot.client.render.ShootTextureResolver} (client
 * classes without an event-bus subscription).</p>
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
     * <p>Silently does nothing when the client player is absent, the main-hand
     * item is not a framework gun, or the stack carries no {@code gun_data}
     * component &mdash; matching the defensive null-guard pattern in
     * {@link org.yanbwe.modularshoot.network.GunSyncService#syncToPlayer}.</p>
     *
     * @param packet the authoritative gun-data snapshot from the server
     */
    public static void handlePacket(GunSyncS2CPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return;
        }
        @Nullable GunData existing = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        if (existing == null) {
            return;
        }
        GunData synced = rebuildGunData(existing, packet);
        mainHand.set(ModularShootDataComponents.GUN_DATA.get(), synced);
    }

    /**
     * Reconstructs a {@link GunData} from the packet, preserving the immutable
     * {@code gunId} and {@code gunInstanceUuid} from the existing client data
     * while overwriting the mutable plugin list, modifier version and state
     * map from the server snapshot.
     *
     * @param existing the current client-side gun data (source of gunId/uuid)
     * @param packet   the server snapshot (source of plugins/version/state)
     * @return a new immutable {@link GunData} ready to write onto the stack
     */
    private static GunData rebuildGunData(GunData existing, GunSyncS2CPacket packet) {
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
