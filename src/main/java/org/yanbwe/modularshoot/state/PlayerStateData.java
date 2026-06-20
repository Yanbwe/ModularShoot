package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Codec;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable holder for a player's per-player state map.
 *
 * <p>Stores the per-player state payload (设计文档 §三层归属 — per-player 域)
 * as a {@link CompoundTag} in the same entry format as
 * {@link org.yanbwe.modularshoot.component.GunData#state}: each state id is
 * a child compound keyed by the id's string form, containing a {@code "type"}
 * field and an optional {@code "value"} field. The shared low-level
 * operations live in {@link GunStateStorage} and are reused here via
 * delegation so that per-gun and per-player state share one serialisation
 * path.</p>
 *
 * <p>Persisted on the player's save data through a NeoForge
 * {@link net.neoforged.neoforge.attachment.AttachmentType} (see
 * {@link ModularShootAttachmentTypes#PLAYER_STATE}), with {@link #CODEC}
 * for NBT serialisation and {@link #STREAM_CODEC} for client
 * synchronisation. {@code copyOnDeath} is enabled on the attachment so the
 * payload survives respawn (设计文档 §持久化与同步 — per-player).</p>
 *
 * <p>All mutating operations return a new {@link PlayerStateData}; the
 * instance is effectively immutable. Callers must treat the
 * {@link #stateTag()} result as read-only.</p>
 *
 * @see ModularShootAttachmentTypes#PLAYER_STATE
 * @see GunStateStorage
 * @see org.yanbwe.modularshoot.component.GunData
 */
public final class PlayerStateData {
    /** Backing NBT tag storing the encoded state map (same format as GunData.state). */
    private final CompoundTag stateTag;

    /**
     * Creates an empty per-player state payload.
     */
    public PlayerStateData() {
        this(new CompoundTag());
    }

    /**
     * Creates a per-player state payload wrapping the given tag.
     *
     * @param stateTag the encoded state map; stored by reference (callers
     *                 must not mutate it afterwards)
     */
    public PlayerStateData(CompoundTag stateTag) {
        this.stateTag = stateTag;
    }

    /**
     * Returns the backing {@link CompoundTag}.
     *
     * @return the encoded state map (not a copy; treat as read-only)
     */
    public CompoundTag stateTag() {
        return stateTag;
    }

    /**
     * Returns a typed map view of the per-player state payload.
     *
     * <p>Delegates to {@link GunStateStorage#toMap}. Entries whose state id
     * is not registered in the {@code modularshoot:states} registry are
     * silently skipped.</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return a mutable map of state id to decoded value
     */
    public Map<ResourceLocation, Object> stateMap(RegistryAccess registryAccess) {
        return GunStateStorage.toMap(stateTag, registryAccess);
    }

    /**
     * Reads a single state value from the per-player state payload.
     *
     * <p>Delegates to {@link GunStateStorage#getStateValue}. When the state
     * id is registered but absent from the tag, the registry's
     * {@link StateDefinition#defaultValue()} is returned; when the id is
     * not registered, {@code null} is returned.</p>
     *
     * @param stateId        the state id to read
     * @param registryAccess the runtime registry view
     * @return the decoded value, the registry default value when the id is
     *         registered but absent, or {@code null} when the id is not
     *         registered
     */
    @Nullable
    public Object getStateValue(ResourceLocation stateId, RegistryAccess registryAccess) {
        return GunStateStorage.getStateValue(stateTag, stateId, registryAccess);
    }

    /**
     * Returns a new {@link PlayerStateData} with a single state value updated.
     *
     * @param stateId        the state id to write
     * @param value          the value to write; {@code null} is only valid
     *                       for UUID-typed states
     * @param registryAccess the runtime registry view
     * @return a new immutable {@link PlayerStateData} with the entry updated
     * @throws IllegalArgumentException when the value's runtime type does
     *         not match the registered declared type
     */
    public PlayerStateData withStateValue(
            ResourceLocation stateId, @Nullable Object value, RegistryAccess registryAccess) {
        return new PlayerStateData(
                GunStateStorage.setStateValue(stateTag, stateId, value, registryAccess));
    }

    /**
     * Returns a new {@link PlayerStateData} with a single state key removed.
     *
     * <p>No registry access is required because removal does not need to
     * know the value type.</p>
     *
     * @param stateId the state id to remove
     * @return a new immutable {@link PlayerStateData} with the entry removed
     */
    public PlayerStateData clearStateValue(ResourceLocation stateId) {
        return new PlayerStateData(GunStateStorage.clearStateValue(stateTag, stateId));
    }

    /**
     * Codec for NBT persistence of the per-player state payload.
     *
     * <p>Delegates to {@link CompoundTag#CODEC} via {@code xmap}, matching
     * the {@link org.yanbwe.modularshoot.component.GunData#state} field
     * codec so both storage domains share the same wire/disk format.</p>
     */
    public static final Codec<PlayerStateData> CODEC =
            CompoundTag.CODEC.xmap(PlayerStateData::new, PlayerStateData::stateTag);

    /**
     * Stream codec for client synchronisation of the per-player state payload.
     *
     * <p>Writes the backing {@link CompoundTag} with
     * {@code writeNbt} and reads it back with {@code readNbt}. A null read
     * (empty payload on the wire) yields an empty {@link PlayerStateData}.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerStateData> STREAM_CODEC =
            StreamCodec.of(PlayerStateData::encode, PlayerStateData::decode);

    /**
     * Encodes the payload to the network buffer.
     *
     * @param buf   the target buffer
     * @param data  the payload to encode
     */
    private static void encode(RegistryFriendlyByteBuf buf, PlayerStateData data) {
        buf.writeNbt(data.stateTag);
    }

    /**
     * Decodes a payload from the network buffer.
     *
     * @param buf the source buffer
     * @return a new {@link PlayerStateData}; empty when the buffer held a
     *         null payload
     */
    private static PlayerStateData decode(RegistryFriendlyByteBuf buf) {
        final CompoundTag tag = buf.readNbt();
        return new PlayerStateData(tag != null ? tag : new CompoundTag());
    }
}
