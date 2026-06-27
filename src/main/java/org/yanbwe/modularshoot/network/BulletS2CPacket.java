package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.ClientBulletSnapshot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client bullet sync packet (设计文档 §BulletS2CPacket / §同步策略).
 *
 * <p>Sent by the server every tick (via {@code BulletSyncService}) to inform
 * clients of bullet state changes within their render distance. The server
 * remains the sole authority on bullet trajectory; clients only interpolate
 * the visual representation. Each packet is built per-player with
 * render-distance culling, so a player never receives bullets they cannot
 * see.</p>
 *
 * <p><b>Incremental sync (设计文档 §同步策略, lines 2042-2043):</b> the first
 * packet for a bullet carries full information (id, position, direction,
 * visual style, size, shooter); subsequent update packets carry only the id
 * and the current position/direction. The packet therefore splits entries
 * into three buckets:</p>
 * <ul>
 *   <li>{@link FullBulletEntry} — newly created bullets (or a forced
 *       full-sync set) carrying every field the client needs to instantiate
 *       a {@code BulletRenderObject}.</li>
 *   <li>{@link DeltaBulletEntry} — already-known bullets whose
 *       position/direction has changed since the last sync; carries only the
 *       id and the new absolute position/direction (simplified delta — see
 *       design note below).</li>
 *   <li>{@link #removedBulletIds()} — bullet ids that have expired or hit
 *       something since the last sync; the client destroys the
 *       corresponding render objects.</li>
 * </ul>
 *
 * <p><b>Design note — absolute vs relative delta:</b> the design doc says
 * "位置变化量" (position delta). This implementation uses <em>absolute</em>
 * position/direction in {@link DeltaBulletEntry} rather than relative deltas.
 * Absolute values are loss-tolerant: a dropped update packet does not
 * desynchronise subsequent deltas, and a periodic {@link #forceFullSync()}
 * corrects any drift. This trades a few extra bytes per update for robustness
 * and simpler client logic.</p>
 *
 * <p><b>Force-full-sync:</b> when {@link #forceFullSync()} is {@code true},
 * the client clears its entire render-object map and rebuilds from
 * {@link #newBullets()} alone — {@link #updatedBullets()} and
 * {@link #removedBulletIds()} are ignored. The service triggers this
 * periodically (every {@code FULL_SYNC_INTERVAL_TICKS}) to recover from
 * packet loss, and on player join.</p>
 *
 * <p><b>Short-life guarantee (设计文档 §短寿命子弹保证, line 1276):</b> the
 * sync service marks every bullet created this tick and includes them in
 * {@link #newBullets()} at the end of the tick — even if the bullet was
 * already removed by collision in the same Pre simulation step. This ensures
 * high-speed / short-range bullets still appear on the client for at least
 * one render frame.</p>
 *
 * @param newBullets      full-data entries for newly created (or forced
 *                        full-sync) bullets the client should create
 * @param updatedBullets  position/direction-only entries for already-known
 *                        bullets the client should update
 * @param removedBulletIds ids of bullets that have expired since the last
 *                        sync; the client should destroy these render objects
 * @param forceFullSync   {@code true} when the client should clear its entire
 *                        render-object map and rebuild from
 *                        {@link #newBullets()} alone (periodic drift
 *                        recovery / initial sync); {@code false} for a normal
 *                        incremental delta packet
 */
public record BulletS2CPacket(
        List<FullBulletEntry> newBullets,
        List<DeltaBulletEntry> updatedBullets,
        List<Integer> removedBulletIds,
        boolean forceFullSync) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:bullet_s2c}. */
    public static final CustomPacketPayload.Type<BulletS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet_s2c"));

    /**
     * Stream codec that serializes the three entry buckets and the
     * force-full-sync flag.
     *
     * <p>The payload id is <em>not</em> written here — NeoForge writes it
     * automatically around the codec output (see
     * {@link CustomPacketPayload} class docs).</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BulletS2CPacket> STREAM_CODEC =
            StreamCodec.of(BulletS2CPacket::encode, BulletS2CPacket::decode);

    /**
     * Creates a force-full-sync packet representing the complete set of
     * in-flight bullets for a dimension. The client clears its render-object
     * map and rebuilds from {@code bullets} alone. Used for periodic drift
     * recovery and initial sync.
     *
     * @param bullets every active bullet visible to the receiving player
     * @return a force-full-sync (reconciling) packet
     */
    public static BulletS2CPacket fullSync(List<FullBulletEntry> bullets) {
        return new BulletS2CPacket(bullets, List.of(), List.of(), true);
    }

    /**
     * Creates an incremental delta packet carrying the three sync buckets.
     * The client creates new render objects from {@code newBullets}, updates
     * existing ones from {@code updatedBullets}, and destroys those in
     * {@code removedBulletIds}. It does <em>not</em> cull bullets absent from
     * the lists — only explicit {@code removedBulletIds} are removed.
     *
     * @param newBullets      newly created bullets (full data)
     * @param updatedBullets  already-known bullets with new position/direction
     * @param removedBulletIds bullets that have expired since the last sync
     * @return an incremental delta packet
     */
    public static BulletS2CPacket delta(
            List<FullBulletEntry> newBullets,
            List<DeltaBulletEntry> updatedBullets,
            List<Integer> removedBulletIds) {
        return new BulletS2CPacket(newBullets, updatedBullets, removedBulletIds, false);
    }

    /**
     * Encodes this packet's payload into the buffer: new-bullets count +
     * entries, updated-bullets count + entries, removed-ids count + ints,
     * then the {@code forceFullSync} flag.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, BulletS2CPacket packet) {
        encodeFullEntries(buf, packet.newBullets);
        encodeDeltaEntries(buf, packet.updatedBullets);
        encodeRemovedIds(buf, packet.removedBulletIds);
        buf.writeBoolean(packet.forceFullSync);
    }

    /**
     * Decodes a packet from the buffer by reading the three buckets and the
     * force-full-sync flag.
     *
     * @param buf the source buffer
     * @return a new {@link BulletS2CPacket} read from the buffer
     */
    private static BulletS2CPacket decode(RegistryFriendlyByteBuf buf) {
        List<FullBulletEntry> newBullets = decodeFullEntries(buf);
        List<DeltaBulletEntry> updatedBullets = decodeDeltaEntries(buf);
        List<Integer> removedBulletIds = decodeRemovedIds(buf);
        boolean forceFullSync = buf.readBoolean();
        return new BulletS2CPacket(newBullets, updatedBullets, removedBulletIds, forceFullSync);
    }

    // --- FullBulletEntry codec ------------------------------------------

    /**
     * Encodes a list of {@link FullBulletEntry} as a count followed by that
     * many entries.
     *
     * @param buf     the target buffer
     * @param entries the full entries to serialize
     */
    private static void encodeFullEntries(RegistryFriendlyByteBuf buf, List<FullBulletEntry> entries) {
        buf.writeInt(entries.size());
        for (FullBulletEntry entry : entries) {
            encodeFullEntry(buf, entry);
        }
    }

    /**
     * Decodes a list of {@link FullBulletEntry} written by
     * {@link #encodeFullEntries}.
     *
     * @param buf the source buffer
     * @return an immutable list of full entries
     */
    private static List<FullBulletEntry> decodeFullEntries(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        List<FullBulletEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(decodeFullEntry(buf));
        }
        return List.copyOf(entries);
    }

    /**
     * Encodes a single {@link FullBulletEntry} into the buffer in fixed
     * field order.
     *
     * @param buf   the target buffer
     * @param entry the full entry to serialize
     */
    private static void encodeFullEntry(RegistryFriendlyByteBuf buf, FullBulletEntry entry) {
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
        ClientBulletSnapshot.STREAM_CODEC.encode(buf, entry.snapshot());
    }

    /**
     * Decodes a single {@link FullBulletEntry} from the buffer, mirroring
     * {@link #encodeFullEntry}.
     *
     * @param buf the source buffer
     * @return a new {@link FullBulletEntry}
     */
    private static FullBulletEntry decodeFullEntry(RegistryFriendlyByteBuf buf) {
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
        ClientBulletSnapshot snapshot = ClientBulletSnapshot.STREAM_CODEC.decode(buf);
        return new FullBulletEntry(
                bulletId, posX, posY, posZ, dirX, dirY, dirZ,
                texture, modelLocation, renderMode, bulletSize, shooterEntityId, snapshot);
    }

    // --- DeltaBulletEntry codec -----------------------------------------

    /**
     * Encodes a list of {@link DeltaBulletEntry} as a count followed by that
     * many entries.
     *
     * @param buf     the target buffer
     * @param entries the delta entries to serialize
     */
    private static void encodeDeltaEntries(RegistryFriendlyByteBuf buf, List<DeltaBulletEntry> entries) {
        buf.writeInt(entries.size());
        for (DeltaBulletEntry entry : entries) {
            encodeDeltaEntry(buf, entry);
        }
    }

    /**
     * Decodes a list of {@link DeltaBulletEntry} written by
     * {@link #encodeDeltaEntries}.
     *
     * @param buf the source buffer
     * @return an immutable list of delta entries
     */
    private static List<DeltaBulletEntry> decodeDeltaEntries(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        List<DeltaBulletEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(decodeDeltaEntry(buf));
        }
        return List.copyOf(entries);
    }

    /**
     * Encodes a single {@link DeltaBulletEntry} — id + 3 position doubles +
     * 3 direction doubles. Visual style is omitted (already known from the
     * initial full entry).
     *
     * @param buf   the target buffer
     * @param entry the delta entry to serialize
     */
    private static void encodeDeltaEntry(RegistryFriendlyByteBuf buf, DeltaBulletEntry entry) {
        buf.writeInt(entry.bulletId());
        buf.writeDouble(entry.posX());
        buf.writeDouble(entry.posY());
        buf.writeDouble(entry.posZ());
        buf.writeDouble(entry.dirX());
        buf.writeDouble(entry.dirY());
        buf.writeDouble(entry.dirZ());
    }

    /**
     * Decodes a single {@link DeltaBulletEntry} from the buffer, mirroring
     * {@link #encodeDeltaEntry}.
     *
     * @param buf the source buffer
     * @return a new {@link DeltaBulletEntry}
     */
    private static DeltaBulletEntry decodeDeltaEntry(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readInt();
        double posX = buf.readDouble();
        double posY = buf.readDouble();
        double posZ = buf.readDouble();
        double dirX = buf.readDouble();
        double dirY = buf.readDouble();
        double dirZ = buf.readDouble();
        return new DeltaBulletEntry(bulletId, posX, posY, posZ, dirX, dirY, dirZ);
    }

    // --- removedBulletIds codec -----------------------------------------

    /**
     * Encodes the removed-bullet-id list as a count followed by that many
     * ints.
     *
     * @param buf the target buffer
     * @param ids the removed bullet ids to serialize
     */
    private static void encodeRemovedIds(RegistryFriendlyByteBuf buf, List<Integer> ids) {
        buf.writeInt(ids.size());
        for (int id : ids) {
            buf.writeInt(id);
        }
    }

    /**
     * Decodes the removed-bullet-id list written by
     * {@link #encodeRemovedIds}.
     *
     * @param buf the source buffer
     * @return an immutable list of removed bullet ids
     */
    private static List<Integer> decodeRemovedIds(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readInt());
        }
        return List.copyOf(ids);
    }

    // --- Nullable ResourceLocation helpers ------------------------------

    /**
     * Writes a nullable {@link ResourceLocation} as a boolean presence flag
     * followed by the location when present.
     *
     * @param buf      the target buffer
     * @param location the location to write, or {@code null}
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
     * Serialized description of a single bullet carrying <em>full</em> data
     * for client sync — used when a bullet is first created or during a
     * forced full-sync.
     *
     * <p>This is a flat, primitive-typed record (rather than embedding
     * {@code Vec3} / {@code BulletStyle}) so the wire format is explicit and
     * allocation-light on the hot per-tick sync path. Position and direction
     * are split into their three scalar components; the visual style is
     * carried as a nullable texture path (billboard mode), a nullable model
     * path (3d mode) and a render-mode tag string.</p>
     *
     * @param bulletId        unique-per-dimension bullet id, correlating 1:1
     *                        with the server {@code BulletRecord}
     * @param posX            world-space x coordinate
     * @param posY            world-space y coordinate
     * @param posZ            world-space z coordinate
     * @param dirX            normalized direction x component
     * @param dirY            normalized direction y component
     * @param dirZ            normalized direction z component
     * @param texture         billboard-mode texture path, or {@code null}
     *                        when the bullet uses 3d mode or has no visual
     * @param modelLocation   3d-mode vanilla JSON model path, or {@code null}
     *                        when the bullet uses billboard mode or has no
     *                        visual
     * @param renderMode      rendering pipeline tag — {@code "billboard"} or
     *                        {@code "3d"} (see
     *                        {@link org.yanbwe.modularshoot.registry.gun.BulletStyle.RenderMode})
     * @param bulletSize      visual scale / collision radius of the bullet
     * @param shooterEntityId network entity id of the shooter for
     *                        client-side owner attribution, or {@code -1}
     *                        when the bullet is ownerless (independent
     *                        firing from traps etc.)
     * @param snapshot        client-side projection of the bullet's frozen
     *                        stats/traits and identity, consumed by
     *                        {@code onVisualTick} hooks to adjust appearance
     *                        in-flight (设计文档 §特性视觉钩子, line 1298);
     *                        never {@code null}
     */
    public record FullBulletEntry(
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
            int shooterEntityId,
            ClientBulletSnapshot snapshot) {
    }

    /**
     * Serialized description of a single bullet carrying only <em>position
     * and direction</em> — used for already-known bullets whose state has
     * changed since the last sync (设计文档 §同步策略, line 2043: "后续更新包
     * 仅包含 ID 和位置变化量").
     *
     * <p>Visual style (texture, model, render mode, size, shooter) is
     * omitted: the client already has it from the initial
     * {@link FullBulletEntry} and these fields do not change in-flight. This
     * reduces per-update bandwidth significantly in multi-bullet scenarios
     * (shotgun pellets, multi-player firefights).</p>
     *
     * <p>Position and direction are <em>absolute</em> values (not relative
     * deltas) so a dropped packet does not desynchronise subsequent updates;
     * a periodic {@link #forceFullSync()} corrects any residual drift.</p>
     *
     * @param bulletId unique-per-dimension bullet id matching the initial
     *                 {@link FullBulletEntry}
     * @param posX     current world-space x coordinate
     * @param posY     current world-space y coordinate
     * @param posZ     current world-space z coordinate
     * @param dirX     current normalized direction x component
     * @param dirY     current normalized direction y component
     * @param dirZ     current normalized direction z component
     */
    public record DeltaBulletEntry(
            int bulletId,
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ) {
    }
}
