package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.network.ClientBulletSnapshot;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client bullet sync packet (设计文档 §BulletS2CPacket / §同步策略;
 * 阶段 2 / 任务 2.3 §全量同步内容寻址).
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
 *       a {@code BulletRenderObject}, <em>except</em> the visual style which
 *       is content-addressed (see below).</li>
 *   <li>{@link DeltaBulletEntry} — already-known bullets whose
 *       position/direction has changed since the last sync; carries only the
 *       id and the new absolute position/direction.</li>
 *   <li>{@link #removedBulletIds()} — bullet ids that have expired or hit
 *       something since the last sync; the client destroys the
 *       corresponding render objects.</li>
 * </ul>
 *
 * <p><b>Content-addressed visual style (阶段 2 / 任务 2.3).</b> The full
 * visual style (texture / model / render mode / scale / tint / attach layers)
 * and the stats/traits snapshot are <em>flight-invariant</em> for a bullet.
 * Instead of re-transmitting them on every full entry (including the periodic
 * force-full-sync), the server assigns each distinct style a stable wire id
 * ({@link BulletStyleContentAddresser}) and carries that id in
 * {@link FullBulletEntry#styleId()}. The full payload ({@link BulletStyleData})
 * is attached ({@link FullBulletEntry#style()}) only on the first transmission
 * of a given style id to a client; afterwards the client reuses its cached
 * copy by id. Delta and full-sync packets therefore only carry the wire id
 * once the client knows it.</p>
 *
 * <p><b>Design note — absolute vs relative delta:</b> position/direction in
 * {@link DeltaBulletEntry} are <em>absolute</em> values. Absolute values are
 * loss-tolerant: a dropped update packet does not desynchronise subsequent
 * deltas, and a periodic {@link #forceFullSync()} corrects any drift.</p>
 *
 * <p><b>Force-full-sync:</b> when {@link #forceFullSync()} is {@code true},
 * the client reconciles its render-object map against
 * {@link #newBullets()} as a diff update — existing ids are updated in place,
 * new ids are created, and ids absent from the set are removed. The style
 * payload is still content-addressed: only style ids the client does not yet
 * know are re-sent in full.</p>
 *
 * <p><b>Short-life guarantee (设计文档 §短寿命子弹保证, line 1276):</b> the
 * sync service marks every bullet created this tick and includes them in
 * {@link #newBullets()} at the end of the tick — even if the bullet was
 * already removed by collision in the same Pre simulation step.</p>
 *
 * @param newBullets      full-data entries for newly created (or forced
 *                        full-sync) bullets the client should create
 * @param updatedBullets  position/direction-only entries for already-known
 *                        bullets the client should update
 * @param removedBulletIds ids of bullets that have expired since the last
 *                        sync; the client should destroy these render objects
 * @param forceFullSync   {@code true} when the client should reconcile its
 *                        render-object map against {@link #newBullets()} as a
 *                        diff update (periodic drift recovery / initial
 *                        sync); {@code false} for a normal incremental delta
 *                        packet
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

    /** Shared empty extension-bytes payload for entries without extra data (审查 E4). */
    private static final byte[] NO_EXTRA = new byte[0];

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
     * in-flight bullets for a dimension. The client reconciles its
     * render-object map against {@code bullets} as a diff update (existing
     * ids updated in place, new ids created, absent ids removed). Used for
     * periodic drift recovery and initial sync.
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

    /**
     * Reads a varint list count with a defensive upper bound (审查 R2): the
     * count can never legitimately exceed the remaining readable bytes
     * because every entry consumes at least one byte. A corrupted or
     * malicious packet claiming a huge count is rejected instead of
     * pre-allocating a giant list.
     *
     * @param buf  the source buffer
     * @param what human-readable bucket name for the error message
     * @return the validated entry count
     * @throws DecoderException when the count is negative or exceeds the
     *                          remaining payload size
     */
    private static int readBoundedCount(RegistryFriendlyByteBuf buf, String what) {
        int count = buf.readVarInt();
        int remaining = buf.readableBytes();
        if (count < 0 || count > remaining) {
            throw new DecoderException("BulletS2CPacket " + what + " count " + count
                    + " exceeds remaining payload (" + remaining + " bytes)");
        }
        return count;
    }

    /**
     * Maps a render-mode serialized name to its enum ordinal for wire
     * encoding. Unknown names (defensive: the server always writes one of the
     * two known modes) map to {@code BILLBOARD} so the wire never carries an
     * out-of-range ordinal.
     */
    /** serializedName &rarr; ordinal, precomputed once (审查优化 P12: 每次线性扫 → O(1)). */
    private static final Map<String, Byte> RENDER_MODE_ORDINALS = buildRenderModeOrdinals();

    private static Map<String, Byte> buildRenderModeOrdinals() {
        Map<String, Byte> map = new java.util.HashMap<>();
        for (BulletStyle.RenderMode mode : BulletStyle.RenderMode.values()) {
            map.put(mode.getSerializedName(), (byte) mode.ordinal());
        }
        return map;
    }

    private static byte renderModeOrdinal(String serializedName) {
        Byte ordinal = RENDER_MODE_ORDINALS.get(serializedName);
        // Unknown names (defensive: the server always writes one of the
        // two known modes) map to BILLBOARD so the wire never carries an
        // out-of-range ordinal.
        return ordinal != null ? ordinal : (byte) BulletStyle.RenderMode.BILLBOARD.ordinal();
    }

    /**
     * Decodes a render-mode ordinal written by {@link #renderModeOrdinal} back
     * to its serialized name, defensively mapping any out-of-range value
     * (malicious or corrupted packet) to {@code BILLBOARD} so decode never
     * throws {@link ArrayIndexOutOfBoundsException} from a direct
     * {@code values()[byte]} index (阶段 7 / 任务 7.1).
     *
     * <p>Package-private for direct unit testing; the decision is pure.</p>
     *
     * @param ordinal the render-mode ordinal read from the wire
     * @return the matching serialized name, or {@code "billboard"} for unknown
     *         values
     */
    static String decodeRenderMode(byte ordinal) {
        BulletStyle.RenderMode[] modes = BulletStyle.RenderMode.values();
        return ordinal >= 0 && ordinal < modes.length
                ? modes[ordinal].getSerializedName()
                : BulletStyle.RenderMode.BILLBOARD.getSerializedName();
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
        buf.writeVarInt(entries.size());
        for (FullBulletEntry entry : entries) {
            encodeFullEntry(buf, entry);
        }
    }

    /**
     * Decodes a list of {@link FullBulletEntry} written by
     * {@link #encodeFullEntries}.
     *
     * @param buf the source buffer
     * @return the freshly built list of full entries (decode product is never
     *         shared or mutated after return)
     */
    private static List<FullBulletEntry> decodeFullEntries(RegistryFriendlyByteBuf buf) {
        int count = readBoundedCount(buf, "full-entry");
        List<FullBulletEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(decodeFullEntry(buf));
        }
        return entries;
    }

    /**
     * Encodes a single {@link FullBulletEntry} into the buffer in fixed
     * field order: id, position, direction, shooter entity id, style id, then
     * (when the full style is attached) the {@link BulletStyleData} payload.
     *
     * @param buf   the target buffer
     * @param entry the full entry to serialize
     */
    private static void encodeFullEntry(RegistryFriendlyByteBuf buf, FullBulletEntry entry) {
        buf.writeVarInt(entry.bulletId());
        buf.writeDouble(entry.posX());
        buf.writeDouble(entry.posY());
        buf.writeDouble(entry.posZ());
        buf.writeDouble(entry.dirX());
        buf.writeDouble(entry.dirY());
        buf.writeDouble(entry.dirZ());
        buf.writeInt(entry.shooterEntityId());
        ClientBulletSnapshot.STREAM_CODEC.encode(buf, entry.snapshot());
        buf.writeVarInt(entry.styleId());
        if (entry.style() == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            encodeBulletStyleData(buf, entry.style());
        }
        encodeExtra(buf, entry.extra());
    }

    /**
     * Decodes a single {@link FullBulletEntry} from the buffer, mirroring
     * {@link #encodeFullEntry}.
     *
     * @param buf the source buffer
     * @return a new {@link FullBulletEntry}
     */
    private static FullBulletEntry decodeFullEntry(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readVarInt();
        double posX = buf.readDouble();
        double posY = buf.readDouble();
        double posZ = buf.readDouble();
        double dirX = buf.readDouble();
        double dirY = buf.readDouble();
        double dirZ = buf.readDouble();
        int shooterEntityId = buf.readInt();
        ClientBulletSnapshot snapshot = ClientBulletSnapshot.STREAM_CODEC.decode(buf);
        int styleId = buf.readVarInt();
        BulletStyleData style = buf.readBoolean() ? decodeBulletStyleData(buf) : null;
        byte[] extra = decodeExtra(buf);
        return new FullBulletEntry(
                bulletId, posX, posY, posZ, dirX, dirY, dirZ,
                shooterEntityId, snapshot, styleId, style, extra);
    }

    /**
     * Writes an extension-bytes payload (审查 E4): a bounded varint length
     * followed by the raw bytes. An empty payload costs one varint byte.
     *
     * @param buf   the target buffer
     * @param extra the extension payload; never {@code null}
     */
    private static void encodeExtra(RegistryFriendlyByteBuf buf, byte[] extra) {
        buf.writeVarInt(extra.length);
        if (extra.length > 0) {
            buf.writeBytes(extra);
        }
    }

    /**
     * Reads an extension-bytes payload written by {@link #encodeExtra}
     * (审查 E4), with the same defensive bound as the entry counts
     * ({@link #readBoundedCount}).
     *
     * @param buf the source buffer
     * @return the extension payload; a shared empty array when absent
     */
    private static byte[] decodeExtra(RegistryFriendlyByteBuf buf) {
        int length = readBoundedCount(buf, "extra");
        if (length == 0) {
            return NO_EXTRA;
        }
        byte[] extra = new byte[length];
        buf.readBytes(extra);
        return extra;
    }

    // --- BulletStyleData codec ------------------------------------------

    /**
     * Encodes the content-addressed style payload: nullable texture, nullable
     * model, render mode ordinal, render scale, composed tint (white sentinel
     * collapses to {@code null}), and the layer list. The per-bullet snapshot
     * is <em>not</em> part of the style payload (审查 E5) — it travels inline
     * on the full entry.
     *
     * @param buf   the target buffer
     * @param style the style payload to serialize
     */
    private static void encodeBulletStyleData(RegistryFriendlyByteBuf buf, BulletStyleData style) {
        encodeNullableResourceLocation(buf, style.texture());
        encodeNullableResourceLocation(buf, style.modelLocation());
        buf.writeByte(renderModeOrdinal(style.renderMode()));
        buf.writeFloat(style.renderScale());
        if (style.composedTint() == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            buf.writeFloat(style.composedTint().x);
            buf.writeFloat(style.composedTint().y);
            buf.writeFloat(style.composedTint().z);
            buf.writeFloat(style.composedTint().w);
        }
        buf.writeVarInt(style.layers().size());
        for (BulletS2CPacket.FullBulletEntry.LayerEntryFull l : style.layers()) {
            buf.writeByte(renderModeOrdinal(l.renderMode()));
            encodeNullableResourceLocation(buf, l.texture());
            encodeNullableResourceLocation(buf, l.model());
            buf.writeBoolean(l.followRotation());
            buf.writeBoolean(l.followScale());
            buf.writeFloat(l.offsetX());
            buf.writeFloat(l.offsetY());
            buf.writeFloat(l.offsetZ());
            buf.writeFloat(l.scale());
            buf.writeFloat(l.tintR());
            buf.writeFloat(l.tintG());
            buf.writeFloat(l.tintB());
            buf.writeFloat(l.tintA());
        }
    }

    /**
     * Decodes a {@link BulletStyleData} written by
     * {@link #encodeBulletStyleData}.
     *
     * @param buf the source buffer
     * @return a new {@link BulletStyleData}
     */
    private static BulletStyleData decodeBulletStyleData(RegistryFriendlyByteBuf buf) {
        ResourceLocation texture = decodeNullableResourceLocation(buf);
        ResourceLocation modelLocation = decodeNullableResourceLocation(buf);
        String renderMode = decodeRenderMode(buf.readByte());
        float renderScale = buf.readFloat();
        @Nullable Vector4f composedTint = null;
        if (buf.readBoolean()) {
            composedTint = new Vector4f(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
        int layerCount = readBoundedCount(buf, "style-layer");
        List<BulletS2CPacket.FullBulletEntry.LayerEntryFull> layers =
                new ArrayList<>(layerCount);
        for (int i = 0; i < layerCount; i++) {
            String layerRenderMode = decodeRenderMode(buf.readByte());
            ResourceLocation layerTexture = decodeNullableResourceLocation(buf);
            ResourceLocation layerModel = decodeNullableResourceLocation(buf);
            boolean followRotation = buf.readBoolean();
            boolean followScale = buf.readBoolean();
            float offsetX = buf.readFloat();
            float offsetY = buf.readFloat();
            float offsetZ = buf.readFloat();
            float scale = buf.readFloat();
            float tintR = buf.readFloat();
            float tintG = buf.readFloat();
            float tintB = buf.readFloat();
            float tintA = buf.readFloat();
            layers.add(new BulletS2CPacket.FullBulletEntry.LayerEntryFull(
                    layerRenderMode, layerTexture, layerModel,
                    followRotation, followScale,
                    offsetX, offsetY, offsetZ,
                    scale, tintR, tintG, tintB, tintA));
        }
        return new BulletStyleData(texture, modelLocation, renderMode, renderScale,
                composedTint, layers);
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
        buf.writeVarInt(entries.size());
        for (DeltaBulletEntry entry : entries) {
            encodeDeltaEntry(buf, entry);
        }
    }

    /**
     * Decodes a list of {@link DeltaBulletEntry} written by
     * {@link #encodeDeltaEntries}.
     *
     * @param buf the source buffer
     * @return the freshly built list of delta entries (decode product is never
     *         shared or mutated after return)
     */
    private static List<DeltaBulletEntry> decodeDeltaEntries(RegistryFriendlyByteBuf buf) {
        int count = readBoundedCount(buf, "delta-entry");
        List<DeltaBulletEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(decodeDeltaEntry(buf));
        }
        return entries;
    }

    /**
     * Encodes a single {@link DeltaBulletEntry} — id + 3 position doubles +
     * 3 direction floats. Visual style is omitted (already known from the
     * initial full entry / cached by style id).
     *
     * <p>Positions use doubles to match the full-entry precision (审查 R3):
     * float positions drift over one block beyond |16M| coordinates and the
     * double&rarr;float hand-off produced a visible position step on the
     * first delta after a full entry. Directions stay float: they are unit
     * vectors in [-1, 1], where float precision is sub-pixel.</p>
     *
     * @param buf   the target buffer
     * @param entry the delta entry to serialize
     */
    private static void encodeDeltaEntry(RegistryFriendlyByteBuf buf, DeltaBulletEntry entry) {
        buf.writeVarInt(entry.bulletId());
        buf.writeDouble(entry.posX());
        buf.writeDouble(entry.posY());
        buf.writeDouble(entry.posZ());
        buf.writeFloat((float) entry.dirX());
        buf.writeFloat((float) entry.dirY());
        buf.writeFloat((float) entry.dirZ());
        encodeExtra(buf, entry.extra());
    }

    /**
     * Decodes a single {@link DeltaBulletEntry} from the buffer, mirroring
     * {@link #encodeDeltaEntry}.
     *
     * @param buf the source buffer
     * @return a new {@link DeltaBulletEntry}
     */
    private static DeltaBulletEntry decodeDeltaEntry(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readVarInt();
        double posX = buf.readDouble();
        double posY = buf.readDouble();
        double posZ = buf.readDouble();
        double dirX = buf.readFloat();
        double dirY = buf.readFloat();
        double dirZ = buf.readFloat();
        byte[] extra = decodeExtra(buf);
        return new DeltaBulletEntry(bulletId, posX, posY, posZ, dirX, dirY, dirZ, extra);
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
        buf.writeVarInt(ids.size());
        for (int id : ids) {
            buf.writeVarInt(id);
        }
    }

    /**
     * Decodes the removed-bullet-id list written by
     * {@link #encodeRemovedIds}.
     *
     * @param buf the source buffer
     * @return the freshly built list of removed bullet ids (decode product is
     *         never shared or mutated after return)
     */
    private static List<Integer> decodeRemovedIds(RegistryFriendlyByteBuf buf) {
        int count = readBoundedCount(buf, "removed-id");
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        return ids;
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
     * <p>The per-bullet dynamic fields (id, position, direction, shooter
     * entity id) are carried inline, while the flight-invariant visual style
     * and stats/traits snapshot are content-addressed: {@link #styleId()}
     * references the client's cache and {@link #style()} carries the full
     * {@link BulletStyleData} payload only on the first transmission of that
     * style id to a client (阶段 2 / 任务 2.3).</p>
     *
     * @param bulletId        unique-per-dimension bullet id, correlating 1:1
     *                        with the server {@code BulletRecord}
     * @param posX            world-space x coordinate
     * @param posY            world-space y coordinate
     * @param posZ            world-space z coordinate
     * @param dirX            normalized direction x component
     * @param dirY            normalized direction y component
     * @param dirZ            normalized direction z component
     * @param shooterEntityId network entity id of the shooter for
     *                        client-side owner attribution, or {@code -1}
     *                        when the bullet is ownerless (independent
     *                        firing from traps etc.)
     * @param snapshot        this bullet's client-side stats/traits/gun-id
     *                        projection, consumed by visual-tick hooks
     *                        (审查 E5 — decoupled from the content-addressed
     *                        style payload because stats legitimately vary
     *                        per bullet); never {@code null}
     * @param styleId         stable content-addressed wire id referencing the
     *                        client's cached {@link BulletStyleData}
     * @param style           the full style payload when this transmission is
     *                        the first for this style id to this client, or
     *                        {@code null} when the client already has it
     *                        cached
     * @param extra           third-party extension bytes contributed via
     *                        {@link BulletSyncExtraRegistry} (审查 E4); an
     *                        empty array when no provider contributes data
     */
    public record FullBulletEntry(
            int bulletId,
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ,
            int shooterEntityId,
            ClientBulletSnapshot snapshot,
            int styleId,
            @Nullable BulletStyleData style,
            byte[] extra) {

        /**
         * Convenience constructor without extension bytes (审查 E4):
         * equivalent to passing an empty {@code extra} payload.
         */
        public FullBulletEntry(
                int bulletId, double posX, double posY, double posZ,
                double dirX, double dirY, double dirZ,
                int shooterEntityId, ClientBulletSnapshot snapshot,
                int styleId, @Nullable BulletStyleData style) {
            this(bulletId, posX, posY, posZ, dirX, dirY, dirZ,
                    shooterEntityId, snapshot, styleId, style, NO_EXTRA);
        }

        /**
         * Wire-side description of one {@code attach_layer} layer. Scalar
         * flattening of {@link org.yanbwe.modularshoot.bullet.ComposedBulletStyle.LayerEntry}
         * (renderMode + texture/model, follow flags, offset XYZ, scale, tint
         * RGBA) so the wire form is compact and forward-typed (no nested
         * Vector4f/Vec3). See spec §4.3 LayerEntry.
         *
         * @param renderMode      {@code "billboard"} or {@code "3d"} tag
         * @param texture         billboard texture path, {@code null} for 3d
         * @param model           3d model path, {@code null} for billboard
         * @param followRotation  whether the layer rotates with the bullet
         * @param followScale     whether the layer inherits base renderScale
         * @param offsetX/Y/Z     positional offset
         * @param scale           per-layer scale multiplier
         * @param tintR/G/B/A      per-layer tint channels
         */
        public record LayerEntryFull(
                String renderMode,
                @Nullable ResourceLocation texture,
                @Nullable ResourceLocation model,
                boolean followRotation,
                boolean followScale,
                float offsetX,
                float offsetY,
                float offsetZ,
                float scale,
                float tintR,
                float tintG,
                float tintB,
                float tintA) {
        }
    }

    /**
     * Serialized description of a single bullet carrying only <em>position
     * and direction</em> — used for already-known bullets whose state has
     * changed since the last sync (设计文档 §同步策略, line 2043: "后续更新包
     * 仅包含 ID 和位置变化量").
     *
     * <p>Visual style (content-addressed by {@link FullBulletEntry} via
     * {@code styleId}) and shooter are omitted: the client already has them
     * from the initial full entry and these fields do not change in-flight.
     * This reduces per-update bandwidth significantly in multi-bullet
     * scenarios (shotgun pellets, multi-player firefights).</p>
     *
     * <p>Position and direction are <em>absolute</em> values (not relative
     * deltas) so a dropped packet does not desynchronise subsequent updates;
     * a periodic {@link #forceFullSync()} corrects any residual drift.</p>
     *
     * <p><b>Wire precision (审查 R3):</b> the id is written as a varint;
     * positions are written as {@code double} to match full-entry precision
     * at far coordinates, while the unit-vector direction components stay
     * {@code float} (sub-pixel precision in [-1, 1]).</p>
     *
     * @param bulletId unique-per-dimension bullet id matching the initial
     *                 {@link FullBulletEntry}
     * @param posX     current world-space x coordinate
     * @param posY     current world-space y coordinate
     * @param posZ     current world-space z coordinate
     * @param dirX     current normalized direction x component
     * @param dirY     current normalized direction y component
     * @param dirZ     current normalized direction z component
     * @param extra    third-party extension bytes contributed via
     *                 {@link BulletSyncExtraRegistry} (审查 E4); an empty
     *                 array when no provider contributes data
     */
    public record DeltaBulletEntry(
            int bulletId,
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ,
            byte[] extra) {

        /**
         * Convenience constructor without extension bytes (审查 E4):
         * equivalent to passing an empty {@code extra} payload.
         */
        public DeltaBulletEntry(
                int bulletId, double posX, double posY, double posZ,
                double dirX, double dirY, double dirZ) {
            this(bulletId, posX, posY, posZ, dirX, dirY, dirZ, NO_EXTRA);
        }
    }
}
