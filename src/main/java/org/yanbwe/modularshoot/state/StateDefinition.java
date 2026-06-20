package org.yanbwe.modularshoot.state;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Immutable definition of a state entry in the {@code modularshoot:states}
 * datapack registry.
 *
 * <p>The registry key (the state id, e.g. {@code examplemod:kill_count}) is
 * supplied by the registry itself and is therefore <strong>not</strong> a
 * field of this record. Callers obtain it from the registry holder/key.</p>
 *
 * <p>JSON keys (see 设计文档 §状态存储系统):
 * <ul>
 *   <li>{@code domain} — ownership domain: {@code gun}, {@code player}, or
 *       {@code bullet}.</li>
 *   <li>{@code value_type} — value type: {@code int}, {@code long},
 *       {@code double}, {@code float}, {@code boolean}, {@code string}, or
 *       {@code uuid}.</li>
 *   <li>{@code default_value} — initial value; the codec is chosen by
 *       {@code value_type}. Optional for most types (defaults to the
 *       type's zero value); optional for {@code uuid} (defaults to
 *       {@code null}).</li>
 *   <li>{@code display} — display metadata (name, colour, format,
 *       priority, hide_default).</li>
 * </ul>
 *
 * <p>The {@code value_type} field is always written. The
 * {@code default_value} field is optional; when absent, the zero value for
 * the declared {@code value_type} is used.</p>
 *
 * @param domain       ownership domain of the state
 * @param valueType    declared value type; must match the runtime type of
 *                     {@code defaultValue}
 * @param defaultValue initial value for guns/players/bullets that have no
 *                     stored value yet; type must match {@code valueType}
 * @param display      tooltip display metadata
 */
public record StateDefinition(
        StateDomain domain,
        StateValueType valueType,
        Object defaultValue,
        StateDisplay display
) {
    /**
     * Codec that serializes a typed value as a JSON primitive, preserving
     * the exact Java type. Used for the {@code default_value} field.
     *
     * <p>Encoding writes the value directly (e.g. {@code 42} for an int,
     * {@code "hello"} for a string). Decoding tries each type codec in
     * order and picks the first that succeeds.</p>
     */
    private static final Codec<Object> TYPED_VALUE_CODEC = buildTypedValueCodec();

    @SuppressWarnings("unchecked")
    private static <T> Codec<Object> wrapAsObject(Codec<T> codec) {
        return codec.xmap(obj -> (Object) obj, value -> (T) value);
    }

    private static Codec<Object> eitherOf(Codec<Object> left, Codec<Object> right) {
        return Codec.either(left, right).xmap(
                either -> either.map(l -> l, r -> r),
                obj -> Either.left(obj)
        );
    }

    private static Codec<Object> buildTypedValueCodec() {
        Codec<Object> chain = wrapAsObject(Codec.INT);
        chain = eitherOf(chain, wrapAsObject(Codec.LONG));
        chain = eitherOf(chain, wrapAsObject(Codec.DOUBLE));
        chain = eitherOf(chain, wrapAsObject(Codec.FLOAT));
        chain = eitherOf(chain, wrapAsObject(Codec.BOOL));
        chain = eitherOf(chain, wrapAsObject(Codec.STRING));
        chain = eitherOf(chain, wrapAsObject(UUIDUtil.CODEC));
        return chain;
    }

    public static final Codec<StateDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    StateDomain.CODEC.fieldOf("domain").forGetter(StateDefinition::domain),
                    StateValueType.CODEC.fieldOf("value_type").forGetter(StateDefinition::valueType),
                    TYPED_VALUE_CODEC.optionalFieldOf("default_value")
                            .forGetter(def -> Optional.ofNullable(def.defaultValue())),
                    StateDisplay.CODEC.fieldOf("display").forGetter(StateDefinition::display)
            ).apply(instance, (domain, valueType, defaultValueOpt, display) ->
                    new StateDefinition(domain, valueType, defaultValueOpt.orElseGet(valueType::zeroValue), display))
    );

    /**
     * Convenience factory that uses the {@link StateValueType#zeroValue()}
     * as the default value.
     *
     * @param domain    ownership domain
     * @param valueType declared value type
     * @param display   tooltip display metadata
     * @return a new {@code StateDefinition} with the zero value for
     *         {@code valueType}
     */
    public static StateDefinition of(StateDomain domain, StateValueType valueType, StateDisplay display) {
        return new StateDefinition(domain, valueType, valueType.zeroValue(), display);
    }
}
