package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Server-to-client bullet sync packet (设计文档 §BulletS2CPacket / §同步策略).
 *
 * <p>Sent by the server every tick (via {@code BulletSyncService}) to inform
 * clients of all bullets currently in flight within their render distance.
 * The server remains the sole authority on bullet trajectory; clients only
 * interpolate the visual representation. Each packet is built per-player with
 * render-distance culling, so a player never receives bullets they cannot
 * see.</p>
 *
 * <p><b>Wire format:</b> an {@code int} bullet count followed by that many
 * {@link BulletEntry} records. Each entry encodes the bullet id, position
 * (3 doubles), direction (3 doubles), nullable texture + nullable model
 * location (boolean-flagged), render-mode tag, bullet size (float) and
 * shooter entity id (int, {@code -1} when ownerless). The payload id itself
 * is written automatically by NeoForge around the codec output.</p>
 *
 * <p><b>Short-life guarantee (设计文档 §短寿命子弹保证):</b> the sync service
 * immediately broadcasts an {@link #incremental(List)} packet the moment a
 * bullet is registered, so that short-life bullets — those created and
 * removed within the same tick — still reach clients for at least one
 * render frame. The regular per-tick Post sync ({@link #fullSync(List)})
 * continues to update positions and cull expired bullets.</p>
 *
 * <p><b>Full-sync vs incremental:</b> a {@code fullSync} packet represents
 * the complete set of in-flight bullets for the receiving player; the client
 * reconciles its render-object map and removes any bullet not present. An
 * incremental packet ({@code fullSync = false}) only creates or updates
 * render objects — it never culls, so it can safely carry a single bullet
 * without wiping the rest.</p>
 *
 * @param bullets  the batched bullet entries to sync to the receiving client
 * @param fullSync {@code true} when this packet represents the complete
 *                 in-flight set (client reconciles/removes missing bullets);
 *                 {@code false} for an incremental creation broadcast (client
 *                 only creates/updates, never culls)
 */
public record BulletS2CPacket(List<BulletEntry> bullets, boolean fullSync) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:bullet_s2c}. */
    public static final CustomPacketPayload.Type<BulletS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet_s2c"));

    /**
     * Stream codec that serializes the batched bullet list.
     *
     * <p>The payload id is <em>not</em> written here — NeoForge writes it
     * automatically around the codec output (see
     * {@link CustomPacketPayload} class docs).</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BulletS2CPacket> STREAM_CODEC =
            StreamCodec.of(BulletS2CPacket::encode, BulletS2CPacket::decode);

    /**
     * Creates a full-sync packet representing the complete set of in-flight
     * bullets for a dimension. The client reconciles its render-object map
     * against this packet, removing any bullet not present.
     *
     * @param bullets every active bullet visible to the receiving player
     * @return a full-sync (reconciling) packet
     */
    public static BulletS2CPacket fullSync(List<BulletEntry> bullets) {
        return new BulletS2CPacket(bullets, true);
    }

    /**
     * Creates an incremental packet carrying one or more newly created
     * bullets. The client only creates or updates render objects from this
     * packet; it does <em>not</em> remove bullets absent from the list. This
     * is used for the short-life bullet guarantee (设计文档 §短寿命子弹保证)
     * so that a bullet created and removed within the same tick still reaches
     * the client for at least one render frame.
     *
     * @param bullets the newly created bullet(s) to broadcast
     * @return an incremental (non-reconciling) packet
     */
    public static BulletS2CPacket incremental(List<BulletEntry> bullets) {
        return new BulletS2CPacket(bullets, false);
    }

    /**
     * Encodes this packet's payload into the buffer: count, each bullet
     * entry, then the {@code fullSync} flag.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, BulletS2CPacket packet) {
        List<BulletEntry> bullets = packet.bullets;
        buf.writeInt(bullets.size());
        for (BulletEntry entry : bullets) {
            encodeEntry(buf, entry);
        }
        buf.writeBoolean(packet.fullSync);
    }

    /**
     * Decodes a packet from the buffer by reading the count, that many
     * bullet entries, then the {@code fullSync} flag.
     *
     * @param buf the source buffer
     * @return a new {@link BulletS2CPacket} read from the buffer
     */
    private static BulletS2CPacket decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        List<BulletEntry> bullets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bullets.add(decodeEntry(buf));
        }
        boolean fullSync = buf.readBoolean();
        return new BulletS2CPacket(List.copyOf(bullets), fullSync);
    }

    /**
     * Encodes a single bullet entry into the buffer in fixed field order.
     *
     * @param buf   the target buffer
     * @param entry the bullet entry to serialize
     */
    private static void encodeEntry(RegistryFriendlyByteBuf buf, BulletEntry entry) {
        buf.writeInt(entry.bulletId());
        buf.writeDouble(entry.posX());
        buf.writeDouble(entry.posY());
        buf.writeDouble(entry.posZ());
        buf.writeDouble(entry.dirX());
        buf.writeDouble(entry.dirY());
        buf.writeDouble(entry.dirZ());
        encodeNullableResourceLocation(buf, entry.texture());
        encodeNullableResourceLocation(buf, entry.modelLocation());
        buf.writeUtf(entry.renderMode());
        buf.writeFloat(entry.bulletSize());
        buf.writeInt(entry.shooterEntityId());
    }

    /**
     * Decodes a single bullet entry from the buffer, mirroring
     * {@link #encodeEntry}.
     *
     * @param buf the source buffer
     * @return a new {@link BulletEntry}
     */
    private static BulletEntry decodeEntry(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readInt();
        double posX = buf.readDouble();
        double posY = buf.readDouble();
        double posZ = buf.readDouble();
        double dirX = buf.readDouble();
        double dirY = buf.readDouble();
        double dirZ = buf.readDouble();
        ResourceLocation texture = decodeNullableResourceLocation(buf);
        ResourceLocation modelLocation = decodeNullableResourceLocation(buf);
        String renderMode = buf.readUtf();
        float bulletSize = buf.readFloat();
        int shooterEntityId = buf.readInt();
        return new BulletEntry(
                bulletId, posX, posY, posZ, dirX, dirY, dirZ,
                texture, modelLocation, renderMode, bulletSize, shooterEntityId);
    }

    /**
     * Writes a nullable {@link ResourceLocation} as a boolean presence flag
     * followed by the location when present.
     *
     * @param buf       the target buffer
     * @param location  the location to write, or {@code null}
     */
    private static void encodeNullableResourceLocation(RegistryFriendlyByteBuf buf, @Nullable ResourceLocation location) {
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    /**
     * Reads a nullable {@link ResourceLocation} written by
     * {@link #encodeNullableResourceLocation}.
     *
     * @param buf the source buffer
     * @return the location, or {@code null} when the presence flag was false
     */
    @Nullable
    private static ResourceLocation decodeNullableResourceLocation(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Serialized description of a single bullet for client sync.
     *
     * <p>This is a flat, primitive-typed record (rather than embedding
     * {@code Vec3} / {@code BulletStyle}) so the wire format is explicit and
     * allocation-light on the hot per-tick sync path. Position and direction
     * are split into their three scalar components; the visual style is
     * carried as a nullable texture path (billboard mode), a nullable model
     * path (3d mode) and a render-mode tag string.</p>
     *
     * @param bulletId         unique-per-dimension bullet id, correlating 1:1
     *                         with the server {@code BulletRecord}
     * @param posX             world-space x coordinate
     * @param posY             world-space y coordinate
     * @param posZ             world-space z coordinate
     * @param dirX             normalized direction x component
     * @param dirY             normalized direction y component
     * @param dirZ             normalized direction z component
     * @param texture          billboard-mode texture path, or {@code null}
     *                         when the bullet uses 3d mode or has no visual
     * @param modelLocation    3d-mode vanilla JSON model path, or {@code null}
     *                         when the bullet uses billboard mode or has no
     *                         visual
     * @param renderMode       rendering pipeline tag — {@code "billboard"} or
     *                         {@code "3d"} (see
     *                         {@link org.yanbwe.modularshoot.registry.gun.BulletStyle.RenderMode})
     * @param bulletSize       visual scale / collision radius of the bullet
     * @param shooterEntityId  network entity id of the shooter for
     *                         client-side owner attribution, or {@code -1}
     *                         when the bullet is ownerless (independent
     *                         firing from traps etc.)
     */
    public record BulletEntry(
            int bulletId,
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ,
            @Nullable ResourceLocation texture,
            @Nullable ResourceLocation modelLocation,
            String renderMode,
            float bulletSize,
            int shooterEntityId) {
    }
}
