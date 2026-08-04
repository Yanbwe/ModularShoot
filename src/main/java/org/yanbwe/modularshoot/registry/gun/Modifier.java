package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Pair;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Sealed marker interface for all bullet visual modifiers (设计规格 §3.2).
 * Exactly three concrete permits cover the stacking semantics inspired by
 * the Binding-of-Isaac item model:
 *
 * <ul>
 *   <li>{@link ScaleModifier} &mdash; transform · multiply (uniform float)</li>
 *   <li>{@link TintModifier} &mdash; transform · channel-multiply (Vector4f)</li>
 *   <li>{@link AttachLayerModifier} &mdash; additive · independent layer
 *       (texture or model drawn on top of the base, with its own follow
 *       parameters, offset, per-layer scale and per-layer tint)</li>
 * </ul>
 *
 * <p>A fourth permit, {@link UnsupportedModifier}, is the <b>sentinel</b> an
 * <em>unknown</em> {@code "type"} key decodes to, so a list of modifiers
 * keeps parsing even when one entry uses an unrecognised type key
 * (spec §5: "modifiers 内某条 type 不识别 skip + WARN, 其余继续组合"). The
 * composition layer ({@link
 * org.yanbwe.modularshoot.bullet.VisualCompositionService}) is responsible
 * for skipping that sentinel and emitting one {@code WARN} per occurrence
 * at compose time.</p>
 *
 * <h2>Codec dispatch model</h2>
 *
 * <p>This interface deliberately does <b>not</b> use DFU's
 * {@code Codec.dispatch(...)} / {@code dispatchMap(...)} machinery. Empirical
 * probing of DFU 8.0.16 (the version bound by MC 1.21.1) showed that a
 * straightforward {@code Codec.STRING.fieldOf("type").codec().dispatch(...)}
 * setup fails to round-trip under non-compressed {@code JsonOps}: the
 * dispatch decoder first reads the {@code "type"} string, then routes to a
 * per-type {@link MapCodec}; but the small encoders / decoders around
 * {@code KeyDispatchCodec} end up calling {@code ops.getMap(elem)} on a
 * bare-string {@code "scale"} element and return {@code "Not a JSON object"}.
 * The canonical MC pattern ({@code LoggedChatEvent.CODEC} &
 * {@code SpriteSources.CODEC}) avoids this because the key codec returns a
 * rich {@code Type} token via {@code StringRepresentable.fromEnum}, not a
 * raw String — but that pattern <b>fails the list</b> on an unrecognised key
 * (the {@code fromEnum} codec returns {@code DataResult.error}), violating
 * the spec's "其余继续组合" requirement.</p>
 *
 * <p>The {@link #CODEC} below is a hand-rolled implementation that achieves
 * both: every known type routes to its matching per-record {@link MapCodec}
 * which round-trips as a JSON object; every unknown type decodes to an
 * {@link UnsupportedModifier} sentinel without failing the surrounding
 * list (spec §5). The encoder mirrors {@link KeyDispatchCodec}: it invokes
 * the per-type {@code MapCodec.encode} to write the payload, then adds
 * {@code "type":<tag>} on top, so the wire form is the same JSON object
 * shape a vanilla dispatch produce.</p>
 */
public sealed interface Modifier
        permits ScaleModifier, TintModifier, AttachLayerModifier, UnsupportedModifier {

    /** {@code "type"} dispatch field name. */
    String TYPE_FIELD = "type";

    /**
     * Canonical lowercase type tag identifying this modifier (spec §3.2
     * column "type"). For an {@link UnsupportedModifier} sentinel this is
     * the sentinel tag {@link UnsupportedModifier#TYPE_NAME}, <em>not</em>
     * the offending key; the offending key is carried by
     * {@link UnsupportedModifier#unknownType()}.
     *
     * @return the canonical type tag, never {@code null}
     */
    String type();

    /**
     * Canonical per-element dispatch codec (spec §3.4: "codec: dispatch by
     * 'type'"). Reads the {@code "type"} field, routes to the matching
     * permit's {@link MapCodec} for known types and to a
     * {@link MapCodec#unit} sentinel for unknown types. Encodes each
     * modifier as a JSON object of the form
     * {@code {"type":<tag>, <payload fields>}} so the round-trip is order-
     * stable and the wire form mirrors a vanilla dispatch codec.
     */
    Codec<Modifier> CODEC = new ModifierCodec();

    /**
     * List-of-modifiers codec with per-element tolerance of unknown-type
     * keys (spec §5). Cited by {@link BulletStyle#CODEC} for the
     * {@code modifiers} field; also cited by
     * {@code StateDefinition.StateVisualModifier#CODEC} and
     * {@code Trait#CODEC}.
     */
    Codec<java.util.List<Modifier>> LIST_CODEC = CODEC.listOf();

    /**
     * Hand-rolled dispatch codec implementation. See the top-level
     * javadoc for the rationale (DFU 8.0.16 {@code KeyDispatchCodec}
     * round-trip instability on plain {@code JsonOps}).
     *
     * <p><b>Encode.</b> Select the matching {@link MapCodec} for the actual
     * runtime {@link Modifier} subtype; emit it as a record builder; then add
     * {@code "type":<type()>} as the final entry. The result is a JSON
     * object identical in shape to a vanilla dispatch encode.</p>
     *
     * <p><b>Decode.</b> The input must be a JSON object. Read the
     * {@code "type"} member; if it matches a known type tag, route through
     * that permit's {@link MapCodec}; otherwise emit an
     * {@link UnsupportedModifier} sentinel carrying the offending key (spec
     * §5). A missing {@code "type"} field, a non-object input, or any other
     * shape is treated as unknown-type sentinel (never an error), so the
     * surrounding list of modifiers always keeps parsing.</p>
     */
    final class ModifierCodec implements Codec<Modifier> {
        /**
         * Per-type-tag -> per-record MapCodec lookup. Used by the encode
         * direction (driven by the value's actual {@link Modifier#type()})
         * and by the decode direction (driven by the JSON {@code "type"}).
         */
        private static MapCodec<? extends Modifier> mapCodecFor(String tag) {
            return switch (tag) {
                case ScaleModifier.TYPE_NAME -> ScaleModifier.CODEC;
                case TintModifier.TYPE_NAME -> TintModifier.CODEC;
                case AttachLayerModifier.TYPE_NAME -> AttachLayerModifier.CODEC;
                case UnsupportedModifier.TYPE_NAME -> UnsupportedModifier.unitCodec(UnsupportedModifier.ENCODING_TAG);
                default -> null; // sentinelised by caller
            };
        }

        @Override
        public <T> DataResult<Pair<Modifier, T>> decode(DynamicOps<T> ops, T input) {
            // Non-compressed JsonOps: input must be a JSON map we can read
            // the "type" key from. If absent or unreadable, treat as
            // unknown-type sentinel (no failure).
            final Optional<MapLike<T>> mapOpt = ops.getMap(input).result();
            final Optional<String> typeOpt = mapOpt.flatMap(map -> {
                T v = map.get(TYPE_FIELD);
                return v == null ? Optional.empty() : ops.getStringValue(v).result();
            });
            final String tag = typeOpt.orElse("<missing>");
            final MapCodec<? extends Modifier> map = mapCodecFor(tag);
            if (map == null) {
                // Unknown (or missing) type key -> sentinel; do not fail the list.
                return DataResult.success(Pair.of(
                        new UnsupportedModifier(tag), input));
            }
            // Known type: decode the payload via the matching MapCodec.
            final MapLike<T> mapLike = mapOpt.get();
            return map.decode(ops, mapLike).map(m -> Pair.of((Modifier) m, input));
        }

        @Override
        public <T> DataResult<T> encode(Modifier input, DynamicOps<T> ops, T prefix) {
            // Write the payload object first via the per-type MapCodec,
            // then add "type":<tag> on top. Mirror KeyDispatchCodec.encode.
            final String tag = input.type();
            // UnsupportedModifier's wire form is {"type":<original offending key>};
            // so a sentinel encodes with its unknownType() value as the type
            // field, preserving its round-trip.
            final String wireTag = (input instanceof UnsupportedModifier u)
                    ? u.unknownType() : tag;
            final MapCodec<? extends Modifier> map = mapCodecFor(tag);
            final MapCodec<Modifier> erasumed =
                    (MapCodec<Modifier>) (MapCodec<?>) (map != null
                            ? map : UnsupportedModifier.unitCodec(wireTag));
            final var builder = erasumed.encode(input, ops, ops.mapBuilder());
            builder.add(TYPE_FIELD, ops.createString(wireTag));
            return builder.build(prefix);
        }

        }
}