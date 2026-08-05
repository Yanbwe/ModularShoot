package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector3f;

/**
 * Outline stroke specification shared by per-layer outlines and the whole-gun
 * outline.
 *
 * <p>The colour is an RGB triplet with each component in the range 0..1,
 * alpha controls the stroke opacity (0..1, default 1.0) and width the stroke
 * thickness in pixels (default 1). Pixels just outside the shape's silhouette
 * are painted with the outline colour, which is applied after any tint so the
 * stroke is never tinted.</p>
 *
 * @param color stroke colour, RGB triplet with each component in 0..1
 * @param alpha stroke opacity in 0..1; defaults to 1.0 (fully opaque)
 * @param width stroke thickness in pixels; defaults to 1
 */
public record OutlineSpec(Vector3f color, float alpha, int width) {

    public static final Codec<OutlineSpec> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ExtraCodecs.VECTOR3F.fieldOf("color").forGetter(OutlineSpec::color),
                    Codec.FLOAT.optionalFieldOf("alpha", 1.0F).forGetter(OutlineSpec::alpha),
                    Codec.INT.optionalFieldOf("width", 1).forGetter(OutlineSpec::width)
            ).apply(instance, OutlineSpec::new)
    );
}
