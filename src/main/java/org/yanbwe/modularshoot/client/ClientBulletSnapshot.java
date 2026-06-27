package org.yanbwe.modularshoot.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * Client-side projection of a bullet's server-side snapshot data
 * (设计文档 §特性视觉钩子, line 1298: {@code onVisualTick} 参数 — 快照).
 *
 * <p>This is a slimmed-down, client-safe view of the server-side
 * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot}. It carries only the
 * data that client-side {@code onVisualTick} hooks need to adjust a bullet's
 * appearance in-flight: the frozen {@code stats} map, the activated
 * {@code traits} map, the gun definition id and the shooter uuid. It
 * deliberately omits server-only fields such as the damage-type holder and
 * the per-bullet working-memory state map (设计文档 §子弹快照).</p>
 *
 * <p><strong>Client-safe.</strong> Although this class lives in the
 * {@code client} package, it references only common types
 * ({@link ResourceLocation}, {@link UUID}, {@link Map}) and is safe to load
 * on both the dedicated server and the client. It is embedded inside
 * {@link org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry} so
 * the packet codec (a common class) can serialise it; the server constructs
 * the entry but never decodes it, and the class itself carries no
 * client-only dependencies that would break server class-loading.</p>
 *
 * <p><strong>Immutability.</strong> The {@code stats} and {@code traits}
 * maps are defensively copied on construction and on decode, and
 * unmodifiable views are exposed by the record accessors, so visual-tick
 * hooks cannot mutate the frozen values. This matches the read-only contract
 * for client-visible snapshot data: in-flight mutation of stats/traits
 * happens only on the server (via {@code onTick}, {@code onHit}, etc.) and
 * is re-synced to the client on the next full-sync cycle.</p>
 *
 * <p><strong>Wire format.</strong> The {@link #STREAM_CODEC} writes the
 * stats map (count + entries), the traits map (count + entries), a nullable
 * gun id and a nullable shooter uuid. Map entries are
 * {@code ResourceLocation}-keyed with primitive scalar values, keeping the
 * per-bullet overhead small on the hot sync path.</p>
 *
 * @param stats   attribute id → frozen value (unmodifiable view; never
 *                contains {@code null} keys or values)
 * @param traits  trait id → activated flag (unmodifiable view; never
 *                contains {@code null} keys or values)
 * @param gunId   gun definition id, or {@code null} for independent firing
 * @param shooter shooter uuid, or {@code null} for ownerless sources
 */
public record ClientBulletSnapshot(
        Map<ResourceLocation, Double> stats,
        Map<ResourceLocation, Boolean> traits,
        @Nullable ResourceLocation gunId,
        @Nullable UUID shooter) {

    /**
     * Stream codec that serialises the snapshot to a
     * {@link RegistryFriendlyByteBuf}: stats count + entries, traits count +
     * entries, nullable gun id, nullable shooter uuid.
     *
     * <p>Used by {@link org.yanbwe.modularshoot.network.BulletS2CPacket} to
     * embed the snapshot inside each {@code FullBulletEntry}.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientBulletSnapshot> STREAM_CODEC =
            StreamCodec.of(ClientBulletSnapshot::encode, ClientBulletSnapshot::decode);

    /**
     * Compact canonical constructor: defensive-copies the maps and wraps them
     * in unmodifiable views so the frozen projection cannot be mutated by
     * hook code.
     *
     * @param stats   attribute id → frozen value
     * @param traits  trait id → activated flag
     * @param gunId   gun definition id, or {@code null}
     * @param shooter shooter uuid, or {@code null}
     */
    public ClientBulletSnapshot {
        stats = Map.copyOf(stats);
        traits = Map.copyOf(traits);
    }

    /**
     * Returns the frozen stat value for the given attribute id, or
     * {@code 0.0} if the attribute is absent from the snapshot.
     *
     * <p>Convenience accessor mirroring
     * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot#getStat} so visual
     * hooks can read stats without unpacking the map.</p>
     *
     * @param id the attribute id
     * @return the frozen value, or {@code 0.0} when absent
     */
    public double getStat(ResourceLocation id) {
        return stats.getOrDefault(id, 0.0);
    }

    /**
     * Returns the activated flag for the given trait id, or {@code false} if
     * the trait is absent from the snapshot.
     *
     * <p>Convenience accessor mirroring
     * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot#getTrait} so visual
     * hooks can check whether a trait is active without unpacking the map.</p>
     *
     * @param id the trait id
     * @return the activated flag, or {@code false} when absent
     */
    public boolean getTrait(ResourceLocation id) {
        return traits.getOrDefault(id, false);
    }

    // --- StreamCodec implementation -------------------------------------

    /**
     * Encodes this snapshot into the buffer in fixed field order: stats map,
     * traits map, nullable gun id, nullable shooter uuid.
     *
     * @param buf      the target buffer
     * @param snapshot the snapshot to serialise
     */
    private static void encode(RegistryFriendlyByteBuf buf, ClientBulletSnapshot snapshot) {
        encodeStatMap(buf, snapshot.stats);
        encodeTraitMap(buf, snapshot.traits);
        encodeNullableResourceLocation(buf, snapshot.gunId);
        encodeNullableUuid(buf, snapshot.shooter);
    }

    /**
     * Decodes a snapshot from the buffer, mirroring {@link #encode}.
     *
     * @param buf the source buffer
     * @return a new {@link ClientBulletSnapshot}
     */
    private static ClientBulletSnapshot decode(RegistryFriendlyByteBuf buf) {
        Map<ResourceLocation, Double> stats = decodeStatMap(buf);
        Map<ResourceLocation, Boolean> traits = decodeTraitMap(buf);
        ResourceLocation gunId = decodeNullableResourceLocation(buf);
        UUID shooter = decodeNullableUuid(buf);
        return new ClientBulletSnapshot(stats, traits, gunId, shooter);
    }

    // --- Map codecs -----------------------------------------------------

    /**
     * Encodes the stats map as a count followed by that many
     * {@code ResourceLocation + double} entries.
     *
     * @param buf   the target buffer
     * @param stats the stats map to serialise
     */
    private static void encodeStatMap(RegistryFriendlyByteBuf buf, Map<ResourceLocation, Double> stats) {
        buf.writeInt(stats.size());
        for (Map.Entry<ResourceLocation, Double> entry : stats.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeDouble(entry.getValue());
        }
    }

    /**
     * Decodes the stats map written by {@link #encodeStatMap}.
     *
     * @param buf the source buffer
     * @return an unmodifiable stats map
     */
    private static Map<ResourceLocation, Double> decodeStatMap(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        Map<ResourceLocation, Double> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation key = buf.readResourceLocation();
            double value = buf.readDouble();
            map.put(key, value);
        }
        return Map.copyOf(map);
    }

    /**
     * Encodes the traits map as a count followed by that many
     * {@code ResourceLocation + boolean} entries.
     *
     * @param buf    the target buffer
     * @param traits the traits map to serialise
     */
    private static void encodeTraitMap(RegistryFriendlyByteBuf buf, Map<ResourceLocation, Boolean> traits) {
        buf.writeInt(traits.size());
        for (Map.Entry<ResourceLocation, Boolean> entry : traits.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
    }

    /**
     * Decodes the traits map written by {@link #encodeTraitMap}.
     *
     * @param buf the source buffer
     * @return an unmodifiable traits map
     */
    private static Map<ResourceLocation, Boolean> decodeTraitMap(RegistryFriendlyByteBuf buf) {
        int count = buf.readInt();
        Map<ResourceLocation, Boolean> map = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation key = buf.readResourceLocation();
            boolean value = buf.readBoolean();
            map.put(key, value);
        }
        return Map.copyOf(map);
    }

    // --- Nullable helpers -----------------------------------------------

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
     * Writes a nullable {@link UUID} as a boolean presence flag followed by
     * the uuid when present.
     *
     * @param buf the target buffer
     * @param uuid the uuid to write, or {@code null}
     */
    private static void encodeNullableUuid(RegistryFriendlyByteBuf buf, @Nullable UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeUUID(uuid);
        }
    }

    /**
     * Reads a nullable {@link UUID} written by {@link #encodeNullableUuid}.
     *
     * @param buf the source buffer
     * @return the uuid, or {@code null} when the presence flag was false
     */
    @Nullable
    private static UUID decodeNullableUuid(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }
}
