package org.yanbwe.modularshoot.state;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Encoder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.bullet.StateConditionEvaluator;
import org.yanbwe.modularshoot.registry.gun.Modifier;

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
 *   <li>{@code visual_modifiers} — optional list of conditional visual
 *       modifier batches; each entry pairs an enable condition (which state
 *       to test, optional explicit domain, the op and the typed threshold)
 *       with the modifiers to apply when the condition holds at bullet
 *       creation time. Default empty (设计规格 §3.5).</li>
 * </ul>
 *
 * <p>The {@code value_type} field is always written. The
 * {@code default_value} field is optional; when absent, the zero value for
 * the declared {@code value_type} is used.</p>
 *
 * @param domain          ownership domain of the state
 * @param valueType       declared value type; must match the runtime type of
 *                        {@code defaultValue}
 * @param defaultValue    initial value for guns/players/bullets that have no
 *                        stored value yet; type must match {@code valueType}
 * @param display         tooltip display metadata
 * @param visualModifiers conditional visual modifier batches enabled when
 *                        their condition holds at bullet creation time;
 *                        empty when none. The compact constructor copies
 *                        the list into an immutable one, substituting
 *                        empty when passed {@code null}.
 */
public record StateDefinition(
        StateDomain domain,
        StateValueType valueType,
        Object defaultValue,
        StateDisplay display,
        List<StateVisualModifier> visualModifiers
) {
    /**
     * Compact constructor: substitute an empty list for a {@code null}
     * {@code visualModifiers} argument and copy the list into an immutable
     * snapshot so callers cannot mutate the record's internal state after
     * construction (设计规格 §3.4: aggregated style values frozen at
     * creation time).
     */
    public StateDefinition {
        visualModifiers = visualModifiers == null ? List.of() : List.copyOf(visualModifiers);
    }

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
     * (INT→LONG→DOUBLE→FLOAT→BOOLEAN→UUID→STRING) and picks the first
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
     * {@code UUID} before {@code STRING}. The UUID codec must precede
     * STRING: a valid UUID string (e.g. a {@code value_type=uuid}
     * {@code default_value} written as {@code "550e8400-..."}) is captured
     * by the UUID codec, while a non-UUID string fails the UUID codec and
     * falls through to STRING (eitherOf tries the first codec, then the
     * second on failure). The chain uses {@link UUIDUtil#LENIENT_CODEC}
     * rather than {@link UUIDUtil#CODEC}: in this MC version {@code CODEC}
     * only decodes the 4-int-array form (the encoder's output), while
     * {@code LENIENT_CODEC} accepts both that form and plain UUID strings.</p>
     *
     * <p>{@code INT} and {@code LONG} use the strict variants
     * ({@link #strictIntCodec()} / {@link #strictLongCodec()}) instead of
     * {@link Codec#INT} / {@link Codec#LONG}: the vanilla integer codecs
     * decode any JSON number via {@code Number#intValue()} truncation
     * (Gson {@code getAsInt}), so {@code 3.14} would "succeed" as
     * {@code 3}, {@code 1234567890123} as {@code 1912276171} (int
     * overflow), and {@code 2.5} as {@code 2} — silently destroying
     * fractional and out-of-range values before DOUBLE/LONG/FLOAT are ever
     * tried. The strict variants reject fractional and out-of-range
     * numbers so the chain falls through to the correct codec.</p>
     *
     * @return a codec (used as a decoder) that accepts any of the seven
     *         supported types
     */
    private static Codec<Object> buildDecodeChain() {
        Codec<Object> chain = strictIntCodec();
        chain = eitherOf(chain, strictLongCodec());
        chain = eitherOf(chain, wrapAsObject(Codec.DOUBLE));
        chain = eitherOf(chain, wrapAsObject(Codec.FLOAT));
        chain = eitherOf(chain, wrapAsObject(Codec.BOOL));
        // UUID 优先于 STRING：合法 UUID 字符串被 UUID codec 截获（value_type=uuid
        // 的 default_value 可直接从 JSON 以 "550e8400-..." 形式写入），非 UUID
        // 字符串（"foo"）UUID codec 解码失败，eitherOf 的 first-fails-then-second
        // 语义自动落到 STRING，行为无损。用 LENIENT_CODEC 而非 CODEC：本版本
        // UUIDUtil.CODEC 只接受 int 流（[msb1, msb2, lsb1, lsb2] 数组形式，编码器
        // 即用此形式），无法解析 UUID 字符串；LENIENT = withAlternative(CODEC,
        // STRING_CODEC)，两种形式都接受，round-trip 与手写字符串两不误。
        chain = eitherOf(chain, wrapAsObject(UUIDUtil.LENIENT_CODEC));
        chain = eitherOf(chain, wrapAsObject(Codec.STRING));
        return chain;
    }

    /**
     * Strict integer codec for the decode chain.
     *
     * <p>Only accepts numbers without a fractional part that fit in an
     * {@code int}; anything else fails so the chain can fall through to
     * LONG/DOUBLE. Encode is never used (the dispatch encoder handles
     * encoding).</p>
     *
     * @return a codec that decodes JSON numbers as {@code Integer} iff they
     *         are integral and in range
     */
    private static Codec<Object> strictIntCodec() {
        return wrapAsObject(
                Codec.DOUBLE.flatXmap(
                        value -> {
                            if (!isInteger(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                                return DataResult.error(() -> "Not a valid int: " + value);
                            }
                            return DataResult.success((int) (double) value);
                        },
                        value -> DataResult.error(() -> "Encode is handled by the dispatch encoder")));
    }

    /**
     * Strict long codec for the decode chain.
     *
     * <p>Only accepts numbers without a fractional part that fit in a
     * {@code long}; anything else fails so the chain can fall through to
     * DOUBLE. Encode is never used (the dispatch encoder handles
     * encoding).</p>
     *
     * @return a codec that decodes JSON numbers as {@code Long} iff they
     *         are integral and in range
     */
    private static Codec<Object> strictLongCodec() {
        return wrapAsObject(
                Codec.DOUBLE.flatXmap(
                        value -> {
                            if (!isInteger(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
                                return DataResult.error(() -> "Not a valid long: " + value);
                            }
                            return DataResult.success((long) (double) value);
                        },
                        value -> DataResult.error(() -> "Encode is handled by the dispatch encoder")));
    }

    /**
     * Checks whether the given number has no fractional part.
     *
     * @param value the value to check
     * @return {@code true} if {@code value} is finite and integral
     */
    private static boolean isInteger(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
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
        return new Encoder<>() {
            @Override
            public <T> DataResult<T> encode(Object value, DynamicOps<T> ops, T prefix) {
                final Codec<Object> codec = StateValueType.fromObject(value).codec();
                return codec.encode(value, ops, prefix);
            }
        };
    }

    public static final Codec<StateDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    StateDomain.CODEC.fieldOf("domain").forGetter(StateDefinition::domain),
                    StateValueType.CODEC.fieldOf("value_type").forGetter(StateDefinition::valueType),
                    TYPED_VALUE_CODEC.optionalFieldOf("default_value")
                            .forGetter(def -> Optional.ofNullable(def.defaultValue())),
                    StateDisplay.CODEC.fieldOf("display").forGetter(StateDefinition::display),
                    // lazyInitialized breaks a static-init cycle: the nested
                    // StateVisualModifier.CODEC transitively references this
                    // outer CODEC's TYPED_VALUE_CODEC via VisualCondition, so
                    // building it eagerly while StateDefinition.<clinit> runs
                    // re-enters StateVisualModifier.<clinit> mid-flight and
                    // NPEs on the still-null CODEC field. Deferring to
                    // codec-encode/decode time avoids the cycle.
                    Codec.lazyInitialized(() -> StateVisualModifier.CODEC.listOf())
                            .optionalFieldOf("visual_modifiers", List.of())
                            .forGetter(StateDefinition::visualModifiers)
            ).apply(instance, (domain, valueType, defaultValueOpt, display, visualModifiers) ->
                    new StateDefinition(domain, valueType, defaultValueOpt.orElseGet(valueType::zeroValue),
                            display, visualModifiers))
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
        return new StateDefinition(domain, valueType, valueType.zeroValue(), display, List.of());
    }

    /**
     * One conditional binding: an enable condition plus the modifiers to
     * apply when the condition holds at bullet creation time
     * (设计规格 §3.5). The modifiers list is copied into an immutable
     * snapshot by the compact constructor.
     *
     * <p>JSON shape:
     * <pre>{@code
     * {
     *   "condition": {
     *     "state": "modularshoot:killstreak",
     *     "domain": "bullet",      // optional; caller resolves when omitted
     *     "op": ">=",
     *     "value": 3                // typed threshold (number / boolean / string)
     *   },
     *   "modifiers": [
     *     { "type": "tint",  "color": [1.0, 0.2, 0.2] },
     *     { "type": "scale", "value": 1.2 }
     *   ]
     * }
     * }</pre>
     *
     * <p>The modifiers list uses {@link Modifier#LIST_CODEC} so a single
     * unrecognised {@code "type"} key decodes to an
     * {@link org.yanbwe.modularshoot.registry.gun.UnsupportedModifier}
     * sentinel rather than failing the whole batch (spec §5).</p>
     *
     * @param condition the enable condition (never {@code null})
     * @param modifiers the modifiers applied when the condition holds;
     *                  empty when none. {@code null} is normalised to empty.
     */
    public record StateVisualModifier(VisualCondition condition, List<Modifier> modifiers) {

        public StateVisualModifier {
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }

        public static final Codec<StateVisualModifier> CODEC = RecordCodecBuilder.create(
                instance -> instance.group(
                        VisualCondition.CODEC.fieldOf("condition").forGetter(StateVisualModifier::condition),
                        Modifier.LIST_CODEC.fieldOf("modifiers").forGetter(StateVisualModifier::modifiers)
                ).apply(instance, StateVisualModifier::new));
    }

    /**
     * Single enabling condition for a {@link StateVisualModifier}: which
     * state to test, an optional explicit domain (caller resolves source
     * when omitted), the comparison op and the typed threshold value
     * (设计规格 §3.5).
     *
     * <p>When {@link #domain} is absent, {@link
     * org.yanbwe.modularshoot.bullet.VisualCompositionService} resolves the
     * source by trying per-bullet state first, then per-gun state (spec
     * §3.5 domain resolution). When present, only the explicitly-named
     * domain is consulted (e.g. {@code "bullet"} reads from the
     * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot} state map;
     * {@code "gun"} from the {@link org.yanbwe.modularshoot.component.GunData}
     * state map); {@code "player"} is unsupported and the caller skips +
     * warns.</p>
     *
     * <p>The threshold {@link #value} is a typed JSON primitive: a number
     * (for INT/LONG/DOUBLE/FLOAT states), a boolean (for BOOLEAN) or a
     * string (for STRING). UUID thresholds are not supported (the caller
     * skips + warns per spec §5).</p>
     *
     * @param state  the state id to test, never {@code null}
     * @param domain optional explicit domain; empty when caller resolves
     * @param op     the comparison operator, never {@code null}
     * @param value  the typed threshold value; may be {@code null} only on
     *               decode when the field is absent (the codec substitutes
     *               {@code null}; {@link StateConditionEvaluator#eval}
     *               returns {@code false} for {@code null} thresholds)
     */
    public record VisualCondition(
            ResourceLocation state,
            Optional<StateDomain> domain,
            StateConditionEvaluator.Op op,
            Object value) {

        public static final Codec<VisualCondition> CODEC = Codec.lazyInitialized(() -> RecordCodecBuilder.create(
                instance -> instance.group(
                        ResourceLocation.CODEC.fieldOf("state").forGetter(VisualCondition::state),
                        StateDomain.CODEC.optionalFieldOf("domain").forGetter(VisualCondition::domain),
                        StateConditionEvaluator.Op.CODEC.fieldOf("op").forGetter(VisualCondition::op),
                        TYPED_VALUE_CODEC.optionalFieldOf("value").forGetter(vc -> Optional.ofNullable(vc.value()))
                ).apply(instance, (state, domain, op, valueOpt) ->
                        new VisualCondition(state, domain, op, valueOpt.orElse(null)))));
    }
}
