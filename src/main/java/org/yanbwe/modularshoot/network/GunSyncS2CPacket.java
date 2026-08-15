package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Server-to-client gun data sync packet (设计文档 §GunSyncS2CPacket, lines
 * 2038-2059; 阶段 2 / 任务 2.3 §GunSync 状态 diff).
 *
 * <p>Sent by the server to push the authoritative {@link
 * org.yanbwe.modularshoot.component.GunData GunData} of the player's main-hand
 * gun to the client so the client's gun model, plugin visual overlays, and HUD
 * state text stay in sync with the server. This is the primary anti-desync
 * channel for gun state.</p>
 *
 * <p><b>Trigger scenarios</b> (handled by {@link GunSyncService}):</p>
 * <ol>
 *   <li>Player switches the main-hand item;</li>
 *   <li>After a plugin is installed / uninstalled;</li>
 *   <li>Player logs into the world (full sync);</li>
 *   <li>Per-gun state is modified by hooks (flushed next tick).</li>
 * </ol>
 *
 * <p><b>Two modes (阶段 2 / 任务 2.3).</b> Scenarios 1-3 are <em>structural</em>
 * changes (plugin list / modifier version may change) and are sent as a full
 * snapshot via {@link #full}: the packet carries the complete plugin list, the
 * {@code modifierVersion} and the <em>entire</em> state map, and the client
 * replaces its copy wholesale. Scenario 4 is a <em>per-gun state diff</em> sent
 * via {@link #statePatch}: the plugin list and modifier version are unchanged,
 * so the packet only carries the changed state keys ({@link #state()}) plus the
 * keys to remove ({@link #removedStateKeys()}). The client merges the patch
 * into its existing state instead of replacing it — this avoids re-transmitting
 * the full plugin list + NBT state map every time a single state value changes
 * (e.g. ammo decrement while shooting).</p>
 *
 * <p><b>Wire format:</b> the payload id is <em>not</em> written by the codec
 * &mdash; NeoForge writes it automatically around the codec output (see
 * {@link CustomPacketPayload} class docs). The codec writes, in order: the
 * {@code gunInstanceUuid} ownership marker ({@code UUID}), the {@code hotbarSlot}
 * ({@code int}), the plugin count + each {@link PluginSyncEntry},
 * {@code modifierVersion} ({@code int}), {@code state} ({@code NBT}), the
 * removed-state-key count + keys, and the {@code statePatch} flag
 * ({@code boolean}). For a state-patch packet the plugin count is {@code 0}
 * and {@code removedStateKeys} carries the removals; for a full packet
 * {@code removedStateKeys} is empty and {@code statePatch} is {@code false}.</p>
 *
 * @param gunInstanceUuid the {@code gunInstanceUuid} of the synced gun — one
 *                        half of the ownership marker the client uses to
 *                        reject stale snapshots (描边污染修复). Note that
 *                        copied gun stacks share the same uuid, so the uuid
 *                        alone cannot tell two stacks apart; the slot can
 * @param hotbarSlot      the hotbar slot (0..8) the snapshot was read from
 *                        on the server — the other half of the ownership
 *                        marker. The client accepts the snapshot only when
 *                        this equals the current {@code selected} hotbar
 *                        slot, so a snapshot for a slot the player has
 *                        switched away from is dropped even when the uuid
 *                        matches a copied twin
 * @param plugins         the installed plugin list of the synced gun; empty
 *                        for a state-patch packet (the plugin list is
 *                        unchanged)
 * @param modifierVersion the anti-cheat modifier version counter of the gun;
 *                        meaningful only for a full packet
 * @param state           for a full packet, the complete per-gun state
 *                        compound; for a state patch, only the changed state
 *                        keys (partial map)
 * @param removedStateKeys state keys to remove client-side; non-empty only on
 *                        a state patch
 * @param statePatch      {@code true} when this is a state diff (merge
 *                        semantic), {@code false} when it is a structural full
 *                        sync (replace semantic)
 */
public record GunSyncS2CPacket(
        UUID gunInstanceUuid,
        int hotbarSlot,
        List<PluginSyncEntry> plugins,
        int modifierVersion,
        CompoundTag state,
        List<String> removedStateKeys,
        boolean statePatch
) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:gun_sync_s2c}. */
    public static final CustomPacketPayload.Type<GunSyncS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "gun_sync_s2c"));

    /**
     * Stream codec that serializes all fields of this packet.
     *
     * <p>Encoding order: {@code gunInstanceUuid} &rarr; {@code hotbarSlot}
     * &rarr; plugin count &rarr; each {@link PluginSyncEntry} &rarr;
     * {@code modifierVersion} &rarr; {@code state} NBT &rarr; removed-state-key
     * count &rarr; keys &rarr; {@code statePatch}. The payload id is written by
     * NeoForge, not here.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GunSyncS2CPacket> STREAM_CODEC =
            StreamCodec.of(GunSyncS2CPacket::encode, GunSyncS2CPacket::decode);

    /**
     * Creates a structural full-sync packet: complete plugin list, modifier
     * version, and the entire state map (replace semantic on the client).
     *
     * @param gunInstanceUuid the gun instance uuid ownership marker
     * @param hotbarSlot      the hotbar slot the snapshot was read from
     * @param plugins         the complete installed plugin list
     * @param modifierVersion the modifier version counter
     * @param state           the complete per-gun state compound tag
     * @return a full structural sync packet
     */
    public static GunSyncS2CPacket full(
            UUID gunInstanceUuid, int hotbarSlot,
            List<PluginSyncEntry> plugins, int modifierVersion, CompoundTag state) {
        return new GunSyncS2CPacket(gunInstanceUuid, hotbarSlot, plugins,
                modifierVersion, state, List.of(), false);
    }

    /**
     * Creates a state-diff (patch) packet: only the changed state keys plus
     * the keys to remove. The plugin list and modifier version are left empty /
     * zero and must be preserved client-side.
     *
     * @param gunInstanceUuid the gun instance uuid ownership marker
     * @param hotbarSlot      the hotbar slot the snapshot was read from
     * @param patch           the changed-key patch state (see {@link GunStateDiff#diff})
     * @param removedStateKeys state keys to remove (see {@link GunStateDiff#removedKeys})
     * @return a state-patch sync packet
     */
    public static GunSyncS2CPacket statePatch(
            UUID gunInstanceUuid, int hotbarSlot,
            CompoundTag patch, List<String> removedStateKeys) {
        return new GunSyncS2CPacket(gunInstanceUuid, hotbarSlot, List.of(),
                0, patch, removedStateKeys, true);
    }

    /**
     * Immutable snapshot of a single installed plugin, mirroring the fields of
     * {@link org.yanbwe.modularshoot.component.PluginInstance} that the client
     * needs for visual overlay rendering and HUD display.
     *
     * @param pluginId        the plugin definition id in the
     *                        {@code modularshoot:plugins} registry
     * @param instanceUuid    the stable per-instance uuid
     * @param installedTypeId the category id the instance was installed into
     * @param locked          the lock flag
     */
    public record PluginSyncEntry(
            ResourceLocation pluginId,
            UUID instanceUuid,
            ResourceLocation installedTypeId,
            boolean locked
    ) {

        /**
         * Encodes this entry into the buffer.
         *
         * @param buf   the target buffer
         * @param entry the entry to serialize
         */
        private static void encode(RegistryFriendlyByteBuf buf, PluginSyncEntry entry) {
            buf.writeResourceLocation(entry.pluginId);
            buf.writeUUID(entry.instanceUuid);
            buf.writeResourceLocation(entry.installedTypeId);
            buf.writeBoolean(entry.locked);
        }

        /**
         * Decodes a single entry from the buffer.
         *
         * @param buf the source buffer
         * @return a new {@link PluginSyncEntry} read from the buffer
         */
        private static PluginSyncEntry decode(RegistryFriendlyByteBuf buf) {
            ResourceLocation pluginId = buf.readResourceLocation();
            UUID instanceUuid = buf.readUUID();
            ResourceLocation installedTypeId = buf.readResourceLocation();
            boolean locked = buf.readBoolean();
            return new PluginSyncEntry(pluginId, instanceUuid, installedTypeId, locked);
        }
    }

    /**
     * Encodes this packet's payload into the buffer.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, GunSyncS2CPacket packet) {
        buf.writeUUID(packet.gunInstanceUuid);
        buf.writeInt(packet.hotbarSlot);
        buf.writeInt(packet.plugins.size());
        for (PluginSyncEntry entry : packet.plugins) {
            PluginSyncEntry.encode(buf, entry);
        }
        buf.writeInt(packet.modifierVersion);
        buf.writeNbt(packet.state);
        buf.writeInt(packet.removedStateKeys.size());
        for (String key : packet.removedStateKeys) {
            buf.writeUtf(key);
        }
        buf.writeBoolean(packet.statePatch);
    }

    /**
     * Decodes a packet from the buffer.
     *
     * @param buf the source buffer
     * @return a new {@link GunSyncS2CPacket} read from the buffer
     */
    private static GunSyncS2CPacket decode(RegistryFriendlyByteBuf buf) {
        UUID gunInstanceUuid = buf.readUUID();
        int hotbarSlot = buf.readInt();
        int pluginCount = buf.readInt();
        List<PluginSyncEntry> plugins = new ArrayList<>(pluginCount);
        for (int i = 0; i < pluginCount; i++) {
            plugins.add(PluginSyncEntry.decode(buf));
        }
        int modifierVersion = buf.readInt();
        // readNbt() returns null when the tag is an EndTag (empty payload);
        // normalise to an empty CompoundTag so the record invariant holds.
        CompoundTag state = buf.readNbt();
        if (state == null) {
            state = new CompoundTag();
        }
        int removedCount = buf.readInt();
        List<String> removedStateKeys = new ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removedStateKeys.add(buf.readUtf());
        }
        boolean statePatch = buf.readBoolean();
        return new GunSyncS2CPacket(gunInstanceUuid, hotbarSlot, plugins,
                modifierVersion, state, removedStateKeys, statePatch);
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
