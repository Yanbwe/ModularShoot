package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Uniform-positive float scale modifier (设计规格 §3.2 "scale").
 *
 * <p>Composable by {@link org.yanbwe.modularshoot.bullet.VisualCompositionService}
 * via channel-multiplication: all top-level {@code scale} modifiers across
 * all sources have their {@link #value} multiplied into the bullet's
 * {@code renderScale}. NaN / non-positive / non-finite values are skipped +
 * {@code WARN} at compose time (spec §5).</p>
 *
 * @param value uniform scale factor, expected finite &gt; 0
 */
public record ScaleModifier(float value) implements Modifier {

    /** Canonical type tag ({@code "scale"}). Cited by {@link Modifier#CODEC}. */
    public static final String TYPE_NAME = "scale";

    /**
     * Per-record {@link MapCodec} for {@code value}. Dispatch consumes the
     * {@code "type"} field; this codec only declares the payload fields.
     */
    public static final MapCodec<ScaleModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    com.mojang.serialization.Codec.FLOAT.fieldOf("value").forGetter(ScaleModifier::value)
            ).apply(instance, ScaleModifier::new));

    @Override
    public String type() {
        return TYPE_NAME;
    }
}