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
 * 2038-2059).
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
 * <p><b>Wire format:</b> the payload id is <em>not</em> written by the codec
 * &mdash; NeoForge writes it automatically around the codec output (see
 * {@link CustomPacketPayload} class docs). The codec writes, in order: plugin
 * count ({@code int}), each {@link PluginSyncEntry}, {@code modifierVersion}
 * ({@code int}), and {@code state} ({@code NBT}).</p>
 *
 * @param plugins         the installed plugin list of the synced gun; each entry
 *                        mirrors a {@link org.yanbwe.modularshoot.component.PluginInstance}
 * @param modifierVersion the anti-cheat modifier version counter of the gun
 * @param state           the per-gun state compound tag (state id &rarr; value);
 *                        only contains keys already written onto this gun
 */
public record GunSyncS2CPacket(
        List<PluginSyncEntry> plugins,
        int modifierVersion,
        CompoundTag state
) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:gun_sync_s2c}. */
    public static final CustomPacketPayload.Type<GunSyncS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "gun_sync_s2c"));

    /**
     * Stream codec that serializes all fields of this packet.
     *
     * <p>Encoding order: plugin count &rarr; each {@link PluginSyncEntry}
     * &rarr; {@code modifierVersion} &rarr; {@code state} NBT. The payload id
     * is written by NeoForge, not here.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GunSyncS2CPacket> STREAM_CODEC =
            StreamCodec.of(GunSyncS2CPacket::encode, GunSyncS2CPacket::decode);

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
        buf.writeInt(packet.plugins.size());
        for (PluginSyncEntry entry : packet.plugins) {
            PluginSyncEntry.encode(buf, entry);
        }
        buf.writeInt(packet.modifierVersion);
        buf.writeNbt(packet.state);
    }

    /**
     * Decodes a packet from the buffer.
     *
     * @param buf the source buffer
     * @return a new {@link GunSyncS2CPacket} read from the buffer
     */
    private static GunSyncS2CPacket decode(RegistryFriendlyByteBuf buf) {
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
        return new GunSyncS2CPacket(plugins, modifierVersion, state);
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
