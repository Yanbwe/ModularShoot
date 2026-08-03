package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.joml.Vector4f;

/**
 * Channel-wise tint modifier (设计规格 §3.2 "tint").
 *
 * <p>Composable by {@link org.yanbwe.modularshoot.bullet.VisualCompositionService}
 * via per-channel multiplication: all top-level {@code tint} modifiers
 * across all sources have their {@link #color} channels multiplied into the
 * bullet's {@code composedTint}. Negative or NaN channels are skipped +
 * {@code WARN} at compose time; values &gt;1 are clamped to 1.0 (no warn)
 * (spec §5).</p>
 *
 * @param color RGBA tint, {@code (1,1,1,1)} = white identity
 */
public record TintModifier(Vector4f color) implements Modifier {

    /** Canonical type tag ({@code "tint"}). Cited by {@link Modifier#CODEC}. */
    public static final String TYPE_NAME = "tint";

    /**
     * Per-record {@link MapCodec} for the {@code color} field. Dispatch
     * consumes the {@code "type"} field; this codec only declares the payload.
     * Uses {@link SharedColorCodecs#VECTOR4F} so {@code [r,g,b]} defaults
     * alpha to {@code 1.0} (spec §3.2) and the wire form is canonical
     * {@code [r,g,b,a]}.
     */
    public static final MapCodec<TintModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    SharedColorCodecs.VECTOR4F.fieldOf("color").forGetter(TintModifier::color)
            ).apply(instance, TintModifier::new));

    @Override
    public String type() {
        return TYPE_NAME;
    }
}