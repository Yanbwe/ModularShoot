package org.yanbwe.modularshoot.state;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Encoder;
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

    /**
     * Builds an {@code Either} codec used only in the decode chain.
     *
     * <p>The encode side ({@code obj -> Either.left(obj)}) is a placeholder
     * that is never invoked, because {@link #buildTypedValueCodec()} wraps
     * the decode chain with a dispatch encoder via
     * {@link Codec#of(Encoder, Decoder)}. Only the decode side
     * ({@code either -> ...}) is active.</p>
     */
    private static Codec<Object> eitherOf(Codec<Object> left, Codec<Object> right) {
        return Codec.either(left, right).xmap(
                either -> either.map(l -> l, r -> r),
                obj -> Either.left(obj)
        );
    }

    /**
     * Builds the typed value codec with separate encode and decode
     * directions.
     *
     * <p><strong>Decode</strong> tries each type codec in order
     * (INT→LONG→DOUBLE→FLOAT→BOOLEAN→STRING→UUID) and picks the first
     * that succeeds — preserving the original decode behaviour.</p>
     *
     * <p><strong>Encode</strong> dispatches by the value's runtime type
     * via {@link StateValueType#fromObject(Object)} so each value is
     * written with its correct codec. This fixes the previous bug where
     * all values collapsed to the leftmost {@link Codec#INT} encoder,
     * causing {@code ClassCastException} for non-{@code int} types during
     * registry network sync.</p>
     *
     * @return a codec that round-trips all seven supported value types
     */
    private static Codec<Object> buildTypedValueCodec() {
        return Codec.of(buildDispatchEncoder(), buildDecodeChain(), "TypedValue");
    }

    /**
     * Builds the decode chain that tries each type codec in order.
     *
     * <p>Order matters: {@code INT} before {@code LONG} before
     * {@code DOUBLE} before {@code FLOAT} before {@code BOOLEAN} before
     * {@code STRING} before {@code UUID}.</p>
     *
     * @return a codec (used as a decoder) that accepts any of the seven
     *         supported types
     */
    private static Codec<Object> buildDecodeChain() {
        Codec<Object> chain = wrapAsObject(Codec.INT);
        chain = eitherOf(chain, wrapAsObject(Codec.LONG));
        chain = eitherOf(chain, wrapAsObject(Codec.DOUBLE));
        chain = eitherOf(chain, wrapAsObject(Codec.FLOAT));
        chain = eitherOf(chain, wrapAsObject(Codec.BOOL));
        chain = eitherOf(chain, wrapAsObject(Codec.STRING));
        chain = eitherOf(chain, wrapAsObject(UUIDUtil.CODEC));
        return chain;
    }

    /**
     * Builds an encoder that dispatches by the value's runtime type.
     *
     * <p>Uses {@link StateValueType#fromObject(Object)} to select the
     * correct codec for each value, so a {@code Boolean} is encoded with
     * {@link Codec#BOOL}, a {@code Double} with {@link Codec#DOUBLE},
     * etc. The {@code null} UUID zero-value never reaches this encoder
     * because the {@code optionalFieldOf} wrapper in {@link #CODEC}
     * omits the field when {@code defaultValue} is {@code null}.</p>
     *
     * @return an encoder that writes each value with its matching codec
     */
    private static Encoder<Object> buildDispatchEncoder() {
        return (value, ops, prefix) -> {
            final Codec<Object> codec = StateValueType.fromObject(value).codec();
            return codec.encode(value, ops, prefix);
        };
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
