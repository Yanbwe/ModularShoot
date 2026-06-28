package org.yanbwe.modularshoot.state;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Low-level {@link CompoundTag} operations for the per-gun state payload
 * stored in {@link org.yanbwe.modularshoot.component.GunData}.
 *
 * <p>Provides single-key read/write/remove operations that work directly on
 * the {@link CompoundTag} representation, avoiding the full map
 * encode/decode round-trip that
 * {@link StateValueCodecs#encodeStateMap} /
 * {@link StateValueCodecs#decodeStateMap} would require for each
 * single-key access. Whole-map conversions delegate to
 * {@link StateValueCodecs}.</p>
 *
 * <p>The entry format matches {@link StateValueCodecs}: each state id is
 * stored as a child {@link CompoundTag} keyed by the state id's string
 * form, containing a {@code "type"} field (the
 * {@link StateValueType} serializedName) and an optional {@code "value"}
 * field (omitted for {@code null} UUID values). The {@code TYPE_KEY} and
 * {@code VALUE_KEY} constants here must stay in sync with
 * {@link StateValueCodecs}.</p>
 *
 * <p>All methods are pure: they never mutate their input tags. Methods
 * that modify state return new {@link CompoundTag} instances.</p>
 *
 * @see StateValueCodecs
 * @see org.yanbwe.modularshoot.component.GunData
 */
public final class GunStateStorage {
    /** NBT key storing the {@link StateValueType} serializedName. Must match {@link StateValueCodecs}. */
    private static final String TYPE_KEY = "type";

    /** NBT key storing the encoded value tag. Must match {@link StateValueCodecs}. */
    private static final String VALUE_KEY = "value";

    private GunStateStorage() {
    }

    /**
     * Reads a single state value from the state compound tag.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>When the state id is registered: decodes the stored entry using
     *       the registry's declared {@link StateValueType}. If the id is
     *       absent from the tag, returns the registry's
     *       {@link StateDefinition#defaultValue()}.</li>
     *   <li>When the state id is not registered: returns {@code null}
     *       because the value type cannot be determined and no value can
     *       be synthesised.</li>
     * </ul>
     * </p>
     *
     * @param stateTag       the per-gun state compound tag
     * @param stateId        the state id to read
     * @param registryAccess the runtime registry view
     * @return the decoded value, the registry default value when the id is
     *         registered but absent from the tag, or {@code null} when the
     *         id is not registered
     */
    @Nullable
    public static Object getStateValue(
            CompoundTag stateTag, ResourceLocation stateId, RegistryAccess registryAccess) {
        final Optional<StateDefinition> definition = StateRegistry.getState(registryAccess, stateId);
        if (definition.isEmpty()) {
            return null;
        }
        final StateDefinition def = definition.get();
        final String key = stateId.toString();
        if (!stateTag.contains(key)) {
            return def.defaultValue();
        }
        final CompoundTag entryTag = stateTag.getCompound(key);
        final StateValueType type = def.valueType();
        final Tag valueTag = entryTag.contains(VALUE_KEY) ? entryTag.get(VALUE_KEY) : null;
        return StateValueCodecs.decodeValue(type, valueTag, registryAccess);
    }

    /**
     * Writes a single state value into the state compound tag, returning a
     * new {@link CompoundTag}.
     *
     * <p>The declared type is resolved from the
     * {@code modularshoot:states} registry when the state is registered;
     * otherwise it is inferred from the value's runtime class via
     * {@link StateValueType#fromObject(Object)}. When the state is
     * registered, the value's runtime type must match the declared type
     * (checked via {@link StateValueCodecs#isTypeMatch}); a mismatch throws
     * {@link IllegalArgumentException}.</p>
     *
     * @param stateTag       the source state compound tag (not mutated)
     * @param stateId        the state id to write
     * @param value          the value to write; {@code null} is only valid
     *                       for UUID-typed states
     * @param registryAccess the runtime registry view
     * @return a new {@link CompoundTag} with the entry updated
     * @throws IllegalArgumentException when the value's runtime type does
     *         not match the registered declared type
     */
    public static CompoundTag setStateValue(
            CompoundTag stateTag, ResourceLocation stateId,
            @Nullable Object value, RegistryAccess registryAccess) {
        final StateValueType type = resolveTypeForEncode(registryAccess, stateId, value);
        if (!StateValueCodecs.isTypeMatch(type, value)) {
            throw new IllegalArgumentException(
                    "State " + stateId + " declared type " + type.getSerializedName()
                            + " does not match value type "
                            + (value == null ? "null" : value.getClass().getName()));
        }
        final Tag encoded = StateValueCodecs.encodeValue(type, value, registryAccess);
        final CompoundTag entryTag = new CompoundTag();
        entryTag.putString(TYPE_KEY, type.getSerializedName());
        if (encoded != null) {
            entryTag.put(VALUE_KEY, encoded);
        }
        final CompoundTag result = stateTag.copy();
        result.put(stateId.toString(), entryTag);
        return result;
    }

    /**
     * Removes a single state key from the state compound tag, returning a
     * new {@link CompoundTag}.
     *
     * <p>No registry access is required because removal does not need to
     * know the value type. When the key is absent the source tag is
     * returned as a copy (unchanged content).</p>
     *
     * @param stateTag the source state compound tag (not mutated)
     * @param stateId  the state id to remove
     * @return a new {@link CompoundTag} with the entry removed
     */
    public static CompoundTag clearStateValue(CompoundTag stateTag, ResourceLocation stateId) {
        final CompoundTag result = stateTag.copy();
        result.remove(stateId.toString());
        return result;
    }

    /**
     * Decodes the full state compound tag into a typed map view.
     *
     * <p>Delegates to {@link StateValueCodecs#decodeStateMap}. Entries
     * whose state id is not registered in the {@code modularshoot:states}
     * registry are preserved as their raw entry {@link CompoundTag} so a
     * full {@code stateMap()}&harr;{@code withStateMap()} round-trip is
     * lossless (W26 fix).</p>
     *
     * @param stateTag       the per-gun state compound tag
     * @param registryAccess the runtime registry view
     * @return a mutable map of state id to decoded value (or raw entry tag
     *         for unregistered ids)
     */
    public static Map<ResourceLocation, Object> toMap(
            CompoundTag stateTag, RegistryAccess registryAccess) {
        return StateValueCodecs.decodeStateMap(stateTag, registryAccess);
    }

    /**
     * Encodes a typed map view into a state compound tag.
     *
     * <p>Delegates to {@link StateValueCodecs#encodeStateMap}.</p>
     *
     * @param map            the state id to value mapping
     * @param registryAccess the runtime registry view
     * @return a new {@link CompoundTag} containing all encoded entries
     */
    public static CompoundTag fromMap(
            Map<ResourceLocation, Object> map, RegistryAccess registryAccess) {
        return StateValueCodecs.encodeStateMap(map, registryAccess);
    }

    /**
     * Resolves the declared type for encoding, preferring the registry and
     * falling back to runtime type inference.
     *
     * @param registryAccess the runtime registry view
     * @param stateId        the state id
     * @param value          the runtime value (used when unregistered)
     * @return the resolved value type
     */
    private static StateValueType resolveTypeForEncode(
            RegistryAccess registryAccess, ResourceLocation stateId, @Nullable Object value) {
        return StateRegistry.getState(registryAccess, stateId)
                .map(StateDefinition::valueType)
                .orElseGet(() -> StateValueType.fromObject(value));
    }
}
