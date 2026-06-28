package org.yanbwe.modularshoot.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.state.GunStateStorage;

/**
 * Immutable data component stored on a {@code modularshoot:gun} item stack.
 *
 * <p>Holds the runtime per-gun state: which gun definition the stack refers to,
 * a stable instance uuid for bullet-snapshot backtracking, the ordered list of
 * installed plugins, a modifier version counter for anti-cheat rate-control,
 * and the per-gun state map used by the state storage system.</p>
 *
 * <p>The component is persisted with the item NBT so it survives drops, chests,
 * relogs and dimension changes. The framework recomputes the vanilla
 * {@code ATTRIBUTE_MODIFIERS} component from the gun definition plus the
 * installed plugins whenever this data changes.</p>
 *
 * @param gunId           the gun definition id in the {@code modularshoot:guns} registry
 * @param gunInstanceUuid stable per-stack identifier generated at creation time;
 *                        used by {@code resolveGunFromSnapshot} to locate the firing
 *                        gun even if the player has switched main hand
 * @param installedPlugins ordered list of installed plugin instances; order reflects
 *                        install sequence and influences same-priority trait conflicts
 * @param modifierVersion anti-cheat version counter; incremented on every
 *                        install/uninstall/lock change. Sent with shoot packets
 *                        so the server can reject stale-modifier exploits
 * @param state           per-gun state payload (kill stacks, heat, etc.) stored as a
 *                        compound tag for Codec-friendly serialisation. A typed
 *                        {@code Map<ResourceLocation, Object>} view is exposed via
 *                        {@link #stateMap(RegistryAccess)} and the single-key
 *                        accessors {@link #getStateValue}/{@link #withStateValue}/
 *                        {@link #clearStateValue}; the dispatch codecs live in
 *                        {@link org.yanbwe.modularshoot.state.StateValueCodecs}
 */
public record GunData(
        ResourceLocation gunId,
        UUID gunInstanceUuid,
        List<PluginInstance> installedPlugins,
        int modifierVersion,
        CompoundTag state
) {
    public static final Codec<GunData> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("gun_id").forGetter(GunData::gunId),
                    UUIDUtil.CODEC.fieldOf("gun_instance_uuid").forGetter(GunData::gunInstanceUuid),
                    PluginInstance.CODEC.listOf().optionalFieldOf("installed_plugins", List.of()).forGetter(GunData::installedPlugins),
                    Codec.INT.optionalFieldOf("modifier_version", 0).forGetter(GunData::modifierVersion),
                    CompoundTag.CODEC.optionalFieldOf("state", new CompoundTag()).forGetter(GunData::state)
            ).apply(instance, GunData::new)
    );

    /**
     * Factory for a freshly created gun stack: empty plugin list, version 0, empty state.
     *
     * @param gunId           the gun definition id
     * @param gunInstanceUuid the newly generated instance uuid
     * @return a new immutable {@link GunData} with empty plugins, version 0 and empty state
     */
    public static GunData create(ResourceLocation gunId, UUID gunInstanceUuid) {
        return new GunData(gunId, gunInstanceUuid, List.of(), 0, new CompoundTag());
    }

    /**
     * Returns a typed map view of the per-gun state payload.
     *
     * <p>The underlying {@link CompoundTag} is decoded via
     * {@link GunStateStorage#toMap} using the
     * {@code modularshoot:states} registry to dispatch value types.
     * Entries whose state id is not registered are preserved as their raw
     * entry {@link CompoundTag} so a full
     * {@code stateMap()}&harr;{@code withStateMap()} round-trip is lossless
     * (W26 fix).</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return a mutable map of state id to decoded value (or raw entry tag
     *         for unregistered ids)
     */
    public Map<ResourceLocation, Object> stateMap(RegistryAccess registryAccess) {
        return GunStateStorage.toMap(state, registryAccess);
    }

    /**
     * Returns a new {@link GunData} with the state replaced by the encoded
     * form of the given typed map.
     *
     * @param map            the state id to value mapping
     * @param registryAccess the runtime registry view
     * @return a new immutable {@link GunData} with the updated state
     */
    public GunData withStateMap(Map<ResourceLocation, Object> map, RegistryAccess registryAccess) {
        return new GunData(gunId, gunInstanceUuid, installedPlugins, modifierVersion,
                GunStateStorage.fromMap(map, registryAccess));
    }

    /**
     * Reads a single state value from the per-gun state payload.
     *
     * @param stateId        the state id to read
     * @param registryAccess the runtime registry view
     * @return the decoded value, the registry default value when the id is
     *         registered but absent, or {@code null} when the id is not
     *         registered
     */
    @Nullable
    public Object getStateValue(ResourceLocation stateId, RegistryAccess registryAccess) {
        return GunStateStorage.getStateValue(state, stateId, registryAccess);
    }

    /**
     * Returns a new {@link GunData} with a single state value updated.
     *
     * @param stateId        the state id to write
     * @param value          the value to write; {@code null} is only valid
     *                       for UUID-typed states
     * @param registryAccess the runtime registry view
     * @return a new immutable {@link GunData} with the entry updated
     * @throws IllegalArgumentException when the value's runtime type does
     *         not match the registered declared type
     */
    public GunData withStateValue(
            ResourceLocation stateId, @Nullable Object value, RegistryAccess registryAccess) {
        return new GunData(gunId, gunInstanceUuid, installedPlugins, modifierVersion,
                GunStateStorage.setStateValue(state, stateId, value, registryAccess));
    }

    /**
     * Returns a new {@link GunData} with a single state key removed.
     *
     * <p>No registry access is required because removal does not need to
     * know the value type.</p>
     *
     * @param stateId the state id to remove
     * @return a new immutable {@link GunData} with the entry removed
     */
    public GunData clearStateValue(ResourceLocation stateId) {
        return new GunData(gunId, gunInstanceUuid, installedPlugins, modifierVersion,
                GunStateStorage.clearStateValue(state, stateId));
    }
}
