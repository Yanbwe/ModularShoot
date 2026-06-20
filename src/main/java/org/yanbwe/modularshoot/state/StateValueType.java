package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;

/**
 * Value type of a persistent state entry.
 *
 * <p>Each state stores exactly one of these types. The type is declared at
 * registration time and cannot change afterwards. The framework provides
 * codecs for serializing values of each type in datapack JSON.</p>
 *
 * <ul>
 *   <li>{@link #INT} / {@link #LONG} — counters (kill count, shot count).</li>
 *   <li>{@link #DOUBLE} / {@link #FLOAT} — continuous values (heat, charge
 *       progress).</li>
 *   <li>{@link #BOOLEAN} — binary flags (event triggered).</li>
 *   <li>{@link #STRING} — textual state (current mode name).</li>
 *   <li>{@link #UUID} — entity reference (homing target).</li>
 * </ul>
 *
 * <p>Serialized as the lowercase name ({@code "int"}, {@code "long"}, etc.)
 * in datapack JSON.</p>
 *
 * @see org.yanbwe.modularshoot.state.StateDefinition#valueType
 */
public enum StateValueType implements StringRepresentable {
    INT("int"),
    LONG("long"),
    DOUBLE("double"),
    FLOAT("float"),
    BOOLEAN("boolean"),
    STRING("string"),
    UUID("uuid");

    /** Codec that serializes the enum via its lowercase {@link #getSerializedName}. */
    public static final Codec<StateValueType> CODEC = StringRepresentable.fromEnum(StateValueType::values);

    private final String serializedName;

    StateValueType(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }

    /**
     * Returns the raw {@link Codec} for this value type.
     *
     * <p>The returned codec serializes the value directly (e.g. an integer
     * as a JSON number). Used internally by {@link #mapCodec()} and may be
     * used by callers that need to encode/decode a standalone typed value.</p>
     *
     * @return a codec that handles this type's Java representation
     */
    @SuppressWarnings("unchecked")
    public Codec<Object> codec() {
        return (Codec<Object>) (Codec<?>) switch (this) {
            case INT -> Codec.INT;
            case LONG -> Codec.LONG;
            case DOUBLE -> Codec.DOUBLE;
            case FLOAT -> Codec.FLOAT;
            case BOOLEAN -> Codec.BOOL;
            case STRING -> Codec.STRING;
            case UUID -> UUIDUtil.CODEC;
        };
    }

    /**
     * Returns a {@link MapCodec} that reads/writes the {@code default_value}
     * field for this value type.
     *
     * <p>For non-UUID types the field is optional and defaults to
     * {@link #zeroValue()}. For {@link #UUID} the field is optional and
     * defaults to {@code null} (no entity reference), since UUID zero-value
     * is {@code null} and cannot be encoded by {@link UUIDUtil#CODEC}.</p>
     *
     * @return a map codec bound to the {@code "default_value"} field key
     */
    public MapCodec<Object> mapCodec() {
        if (this == UUID) {
            return UUIDUtil.CODEC.optionalFieldOf("default_value")
                    .xmap(opt -> (Object) opt.orElse(null), obj -> Optional.ofNullable((UUID) obj));
        }
        return codec().optionalFieldOf("default_value", zeroValue());
    }

    /**
     * Returns the zero/identity value for this type.
     *
     * <p>Used as the initial value when a state is first accessed on a
     * gun/player/bullet that has no stored value yet.</p>
     *
     * @return the zero value: {@code 0}, {@code 0L}, {@code 0.0},
     *         {@code 0.0f}, {@code false}, {@code ""}, or {@code null}
     *         (for UUID)
     */
    public Object zeroValue() {
        return switch (this) {
            case INT -> 0;
            case LONG -> 0L;
            case DOUBLE -> 0.0;
            case FLOAT -> 0.0f;
            case BOOLEAN -> false;
            case STRING -> "";
            case UUID -> null;
        };
    }

    /**
     * Infers the value type from a Java object's runtime class.
     *
     * <p>Used by the dispatch codec to determine which codec to use for
     * encoding a default value. {@code null} maps to {@link #UUID}.</p>
     *
     * @param obj the value to classify (may be {@code null})
     * @return the matching {@link StateValueType}
     * @throws IllegalArgumentException if the object's type is not one of the
     *         seven supported types
     */
    public static StateValueType fromObject(Object obj) {
        if (obj == null) return UUID;
        if (obj instanceof Integer) return INT;
        if (obj instanceof Long) return LONG;
        if (obj instanceof Double) return DOUBLE;
        if (obj instanceof Float) return FLOAT;
        if (obj instanceof Boolean) return BOOLEAN;
        if (obj instanceof String) return STRING;
        if (obj instanceof UUID) return UUID;
        throw new IllegalArgumentException("Unsupported state value type: " + obj.getClass().getName());
    }
}
