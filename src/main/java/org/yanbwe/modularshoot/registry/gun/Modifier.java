package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Sealed marker interface for all bullet visual modifiers (设计规格 §3.2).
 * Exactly three concrete permits cover the stacking semantics inspired by
 * the Binding-of-Isaac item model:
 *
 * <ul>
 *   <li>{@link ScaleModifier} — transform · multiply (uniform float)</li>
 *   <li>{@link TintModifier} — transform · channel-multiply (Vector4f)</li>
 *   <li>{@link AttachLayerModifier} — additive · independent layer (texture
 *       or model drawn on top of the base, with its own follow params)</li>
 * </ul>
 *
 * <p>A fourth permit, {@link UnsupportedModifier}, is the <b>sentinel</b> an
 * <em>unknown</em> {@code "type"} key decodes to, so a list of modifiers
 * keeps parsing even when one entry uses an unrecognised type key
 * (spec §5: "modifiers 内某条 type 不识别 skip + WARN, 其余继续组合"). The
 * composition layer
 * ({@link org.yanbwe.modularshoot.bullet.VisualCompositionService}) is
 * responsible for skipping that sentinel and emitting one {@code WARN} per
 * occurrence at compose time.</p>
 *
 * <h2>Codec dispatch model</h2>
 *
 * <p>The dispatch shape mirrors the in-engine {@code Default pose generator}
 * (see {@code FilterMask.CODEC} = {@code StringRepresentable.fromEnum(...).dispatch(T::type, Type::codec)}):
 * a {@link Codec} for the key ({@code "type"} field) drives
 * {@link Codec#dispatch(Function, Function)} with a
 * {@code MapCodec<? extends Modifier>} resolution function. Each concrete
 * permit record exposes its Codec as a {@link MapCodec} (via
 * {@link com.mojang.serialization.codecs.RecordCodecBuilder#mapCodec}) so the
 * {@code "type"} field is consumed by the dispatch key-codec and the
 * remaining fields by the per-record MapCodec, no field clash.</p>
 *
 * <p>For an unrecognised {@code "type"} key the resolution function returns
 * a {@link MapCodec#unit(java.util.function.Supplier) MapCodec.unit(Supplier)}
 * that decodes-to (and no-op-encodes) an {@link UnsupportedModifier}
 * carrying the offending key, so the surrounding list of modifiers never
 * fails on a single bad entry. {@code MapCodec.unit} writes no payload of
 * its own, so only the {@code "type"} key is written and re-decoding
 * resolves the same unknown branch again — a lossless round-trip of the
 * sentinel.</p>
 */
public sealed interface Modifier
        permits ScaleModifier, TintModifier, AttachLayerModifier, UnsupportedModifier {

    /** {@code "type"} dispatch field. */
    String TYPE_FIELD = "type";

    /**
     * Canonical lowercase type tag identifying this modifier (spec §3.2 column
     * "type"). For an {@link UnsupportedModifier} sentinel this is the fixed
     * sentinel tag {@link UnsupportedModifier#TYPE_NAME}, <em>not</em> the
     * offending key; the offending key is carried by
     * {@link UnsupportedModifier#unknownType()}.
     *
     * @return the canonical type tag, never {@code null}
     */
    String type();

    /**
     * Canonical per-element dispatch codec (spec §3.4: "codec: dispatch by
     * 'type'"). Driven by {@link Codec#STRING} reading the {@code "type"}
     * field, dispatching to the matching {@link MapCodec} per concrete
     * permit, and decoding unknown-type keys to {@link UnsupportedModifier}
     * rather than failing.
     */
    Codec<Modifier> CODEC = codec();

    /**
     * List-of-modifiers codec with per-element tolerance of unknown-type keys
     * (spec §5). Cited by {@link BulletStyle#CODEC} for the
     * {@code modifiers} field.
     */
    Codec<java.util.List<Modifier>> LIST_CODEC = CODEC.listOf();

    /**
     * Builds the dispatch codec: looks up each permit's {@link MapCodec} by
     * the decoded {@code "type"} string, or returns a
     * {@link MapCodec#unit} for an unknown key.
     */
    private static Codec<Modifier> codec() {
        Codec<String> typeKeyCodec = Codec.STRING.fieldOf(TYPE_FIELD).codec();
        Function<String, MapCodec<? extends Modifier>> resolver = Modifier::resolveMapCodec;
        return typeKeyCodec.dispatch(Modifier::type, resolver);
    }

    /**
     * Resolves a decoded {@code "type"} tag to the matching permit's
     * {@link MapCodec}, or a unit MapCodec emitting an
     * {@link UnsupportedModifier} sentinel for an unrecognised key.
     *
     * @param typeTag the decoded {@code "type"} value, never {@code null}
     * @return either the permit's MapCodec or a unit-sentinel MapCodec
     */
    private static MapCodec<? extends Modifier> resolveMapCodec(@Nullable String typeTag) {
        if (typeTag == null) {
            // Defensive: dispatch should never pass null, but keep the caller
            // honest — emit the sentinel rather than failing the whole list.
            return UnsupportedModifier.unitCodec("<missing>");
        }
        return switch (typeTag) {
            case ScaleModifier.TYPE_NAME -> ScaleModifier.CODEC;
            case TintModifier.TYPE_NAME -> TintModifier.CODEC;
            case AttachLayerModifier.TYPE_NAME -> AttachLayerModifier.CODEC;
            default -> UnsupportedModifier.unitCodec(typeTag);
        };
    }
}