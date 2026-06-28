package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Codec dispatch utilities for the seven supported state value types.
 *
 * <p>Provides single-value encode/decode via the per-type {@link Codec} from
 * {@link StateValueType#codec()}, plus whole state-map
 * ({@code Map<ResourceLocation, Object>}) serialization to and from
 * {@link CompoundTag}.</p>
 *
 * <p>Each state-map entry is stored as a child {@link CompoundTag} containing:
 * <ul>
 *   <li>{@code "type"} — the {@link StateValueType} serializedName
 *       (e.g. {@code "int"});</li>
 *   <li>{@code "value"} — the encoded value tag, omitted when the value is a
 *       {@code null} UUID (the UUID zero value).</li>
 * </ul>
 * </p>
 *
 * <p><b>Role of the {@code "type"} field (S31).</b> The {@code "type"} field
 * is <em>write-only</em> during normal operation: {@link #encodeStateMap}
 * always writes it, but {@link #decodeStateMap} resolves the value type from
 * the {@code modularshoot:states} registry instead of reading it back. The
 * field is retained as an <em>encoding fallback / diagnostic aid</em>: it
 * allows out-of-band inspection of saved state (e.g. via NBT dumps or
 * external tooling) and provides a recovery path for unregistered entries
 * whose original type can no longer be inferred from the registry. For
 * unregistered entries the raw entry tag (including the {@code "type"}
 * field) is carried forward unchanged so a full
 * {@code stateMap()}&harr;{@code withStateMap()} round-trip is lossless.</p>
 *
 * <p>Decoding preserves state ids that are not registered in the
 * {@code modularshoot:states} registry by carrying their raw entry
 * {@link CompoundTag} forward, so a full
 * {@code stateMap()}&harr;{@code withStateMap()} round-trip is lossless
 * (设计文档 §状态存储系统).</p>
 *
 * @see StateValueType#codec()
 * @see StateValueType#zeroValue()
 */
public final class StateValueCodecs {
    /**
     * NBT key storing the {@link StateValueType} serializedName.
     *
     * <p>This field is <em>write-only</em> during normal decode: the decoder
     * resolves the type from the {@code modularshoot:states} registry rather
     * than reading this key. It is retained as an encoding fallback / diagnostic
     * aid for out-of-band NBT inspection and for lossless round-tripping of
     * unregistered entries (S31).</p>
     */
    private static final String TYPE_KEY = "type";

    /** NBT key storing the encoded value tag. */
    private static final String VALUE_KEY = "value";

    private StateValueCodecs() {
    }

    /**
     * Encodes a single state value to an NBT {@link Tag}.
     *
     * <p>Uses the {@link StateValueType#codec()} bound to
     * {@link NbtOps#INSTANCE} via the provider's serialization context
     * (same pattern as NeoForge {@code AttachmentType}).</p>
     *
     * @param type     the declared value type
     * @param value    the value to encode; {@code null} is only valid for
     *                 {@link StateValueType#UUID}, in which case {@code null}
     *                 is returned so the caller omits the value field
     * @param provider the holder lookup providing registry context
     * @return the encoded tag, or {@code null} when encoding a {@code null}
     *         UUID value
     * @throws IllegalStateException if the codec fails to encode the value
     */
    @Nullable
    public static Tag encodeValue(StateValueType type, @Nullable Object value, HolderLookup.Provider provider) {
        if (value == null) {
            // Only UUID has a null zero value; null encodes to "no value field".
            return null;
        }
        final Codec<Object> codec = type.codec();
        final DataResult<Tag> result = codec.encodeStart(
                provider.createSerializationContext(NbtOps.INSTANCE), value);
        return result.getOrThrow(msg -> encodeException(type, msg));
    }

    /**
     * Decodes a single state value from an NBT {@link Tag}.
     *
     * @param type     the declared value type
     * @param tag      the encoded tag, or {@code null} to signal a missing
     *                 value field (returns the type's zero value)
     * @param provider the holder lookup providing registry context
     * @return the decoded value; the type's zero value when {@code tag} is
     *         {@code null}
     * @throws IllegalStateException if the codec fails to decode the tag
     */
    public static Object decodeValue(StateValueType type, @Nullable Tag tag, HolderLookup.Provider provider) {
        if (tag == null) {
            return type.zeroValue();
        }
        final Codec<Object> codec = type.codec();
        final DataResult<Object> result = codec.parse(
                provider.createSerializationContext(NbtOps.INSTANCE), tag);
        return result.getOrThrow(msg -> decodeException(type, msg));
    }

    /**
     * Returns the zero/identity value for a type.
     *
     * <p>Delegates to {@link StateValueType#zeroValue()}.</p>
     *
     * @param type the declared value type
     * @return the zero value (e.g. {@code 0}, {@code false}, {@code ""}, or
     *         {@code null} for UUID)
     */
    @Nullable
    public static Object zeroValue(StateValueType type) {
        return type.zeroValue();
    }

    /**
     * Checks whether a runtime value's type matches the declared type.
     *
     * <p>For {@link StateValueType#UUID}, {@code null} is considered a match
     * because the UUID zero value is {@code null} (设计文档 §状态存储系统).</p>
     *
     * @param type  the declared value type
     * @param value the runtime value to check
     * @return {@code true} if the value's runtime type is compatible with
     *         the declared type
     */
    public static boolean isTypeMatch(StateValueType type, @Nullable Object value) {
        return switch (type) {
            case INT -> value instanceof Integer;
            case LONG -> value instanceof Long;
            case DOUBLE -> value instanceof Double;
            case FLOAT -> value instanceof Float;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
            case UUID -> value == null || value instanceof UUID;
        };
    }

    /**
     * Encodes a state map to a {@link CompoundTag}.
     *
     * <p>Each entry is stored under a key equal to the state id's string
     * form. The value is a child {@link CompoundTag} with a {@code "type"}
     * field (the declared type name) and a {@code "value"} field (the
     * encoded value), the latter omitted for {@code null} UUID values.</p>
     *
     * <p>The declared type is taken from the
     * {@code modularshoot:states} registry when the state is registered;
     * otherwise it is inferred from the value's runtime class via
     * {@link StateValueType#fromObject(Object)}.</p>
     *
     * <p>The {@code "type"} field is written here for diagnostic / fallback
     * purposes but is <em>not</em> read back by {@link #decodeStateMap},
     * which resolves the type from the registry instead (S31). It is still
     * valuable for out-of-band NBT inspection and for lossless round-tripping
     * of unregistered entries.</p>
     *
     * <p>Entries whose value is already a {@link CompoundTag} (carried
     * forward from a prior {@link #decodeStateMap} of an unregistered state
     * id) are passed through unchanged so the
     * {@code stateMap()}&harr;{@code withStateMap()} round-trip is lossless
     * (W26 fix).</p>
     *
     * @param stateMap       the state id to value mapping
     * @param registryAccess the runtime registry view
     * @return a {@link CompoundTag} containing all encoded entries
     */
    public static CompoundTag encodeStateMap(
            Map<ResourceLocation, Object> stateMap, RegistryAccess registryAccess) {
        final CompoundTag result = new CompoundTag();
        for (Map.Entry<ResourceLocation, Object> entry : stateMap.entrySet()) {
            final ResourceLocation stateId = entry.getKey();
            final Object value = entry.getValue();
            if (value instanceof CompoundTag rawEntry) {
                // Unregistered entry preserved from a prior decode: pass the
                // raw entry tag through unchanged so a stateMap()↔withStateMap()
                // round-trip is lossless (W26 fix). A defensive copy keeps the
                // result independent of the input map's value.
                result.put(stateId.toString(), rawEntry.copy());
                continue;
            }
            final StateValueType type = resolveTypeForEncode(registryAccess, stateId, value);
            final Tag encoded = encodeValue(type, value, registryAccess);
            final CompoundTag entryTag = new CompoundTag();
            entryTag.putString(TYPE_KEY, type.getSerializedName());
            if (encoded != null) {
                entryTag.put(VALUE_KEY, encoded);
            }
            result.put(stateId.toString(), entryTag);
        }
        return result;
    }

    /**
     * Decodes a state map from a {@link CompoundTag}.
     *
     * <p>Registered entries are decoded using the registry's declared type;
     * a missing {@code "value"} field yields the type's zero value (used for
     * {@code null} UUID).</p>
     *
     * <p>The {@code "type"} field written by {@link #encodeStateMap} is
     * <em>not</em> read here: the value type is resolved from the
     * {@code modularshoot:states} registry, which is the authoritative
     * source. The {@code "type"} field serves only as an encoding fallback /
     * diagnostic aid (S31). For unregistered entries the raw entry tag
     * (including the {@code "type"} field) is carried forward unchanged, so
     * the field is preserved across round-trips even though it is not
     * consulted for decoding.</p>
     *
     * <p>Entries whose state id is not registered in the
     * {@code modularshoot:states} registry are preserved as their raw entry
     * {@link CompoundTag} (the {@code {type, value}} compound) rather than
     * being skipped, so a full {@code stateMap()}&harr;{@code withStateMap()}
     * round-trip retains them. {@link #encodeStateMap} detects raw
     * {@link CompoundTag} values and passes them through unchanged (W26
     * fix).</p>
     *
     * @param tag            the compound tag produced by
     *                       {@link #encodeStateMap}
     * @param registryAccess the runtime registry view
     * @return a mutable map of state id to decoded value (or raw entry tag
     *         for unregistered ids)
     */
    public static Map<ResourceLocation, Object> decodeStateMap(
            CompoundTag tag, RegistryAccess registryAccess) {
        final Map<ResourceLocation, Object> result = new HashMap<>();
        for (String key : tag.getAllKeys()) {
            final ResourceLocation stateId = ResourceLocation.parse(key);
            final CompoundTag entryTag = tag.getCompound(key);
            final Optional<StateDefinition> definition = lookupState(registryAccess, stateId);
            if (definition.isEmpty()) {
                // Preserve unregistered entries as raw entry tags so a full
                // stateMap()↔withStateMap() round-trip is lossless (W26 fix).
                result.put(stateId, entryTag);
                continue;
            }
            final StateValueType type = definition.get().valueType();
            final Object value = entryTag.contains(VALUE_KEY)
                    ? decodeValue(type, entryTag.get(VALUE_KEY), registryAccess)
                    : type.zeroValue();
            result.put(stateId, value);
        }
        return result;
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
        return lookupState(registryAccess, stateId)
                .map(StateDefinition::valueType)
                .orElseGet(() -> StateValueType.fromObject(value));
    }

    /**
     * Looks up a state definition in the {@code modularshoot:states}
     * registry.
     *
     * @param registryAccess the runtime registry view
     * @param stateId        the state id
     * @return the matching {@link StateDefinition}, or empty when the
     *         registry is absent or the id is not registered
     */
    private static Optional<StateDefinition> lookupState(
            RegistryAccess registryAccess, ResourceLocation stateId) {
        return registryAccess.registry(ModularShootRegistries.STATES_KEY)
                .flatMap(registry -> registry.getOptional(stateId));
    }

    private static RuntimeException encodeException(StateValueType type, String message) {
        return new IllegalStateException(
                "Failed to encode state value of type " + type.getSerializedName() + ": " + message);
    }

    private static RuntimeException decodeException(StateValueType type, String message) {
        return new IllegalStateException(
                "Failed to decode state value of type " + type.getSerializedName() + ": " + message);
    }
}
