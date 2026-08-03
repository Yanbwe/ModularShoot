package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import org.joml.Vector4f;

/**
 * Shared {@code [r,g,b]} or {@code [r,g,b,a]} -> {@link Vector4f} codec
 * (设计规格 §3.2). A 3-element list decodes to a {@link Vector4f} with the
 * alpha channel defaulted to {@code 1.0}; a 4-element list is taken verbatim.
 * The encode direction always writes a 4-element list on the wire/JSON for a
 * canonical form, regardless of how the value originated.
 *
 * <p>Extracted as a util so both {@link TintModifier#CODEC} and
 * {@link AttachLayerModifier#CODEC} can cite the one definition, avoiding
 * drift between two near-identical inline copies.</p>
 */
public final class SharedColorCodecs {

    private SharedColorCodecs() {
    }

    /**
     * Vector4f colour codec: {@code [r,g,b]} → alpha {@code 1.0} default,
     * {@code [r,g,b,a]} → verbatim. Encodes canonically to a 4-element list.
     */
    public static final Codec<Vector4f> VECTOR4F = Codec.FLOAT.listOf().flatXmap(
            list -> {
                if (list.size() == 3) {
                    return DataResult.success(new Vector4f(list.get(0), list.get(1), list.get(2), 1.0f));
                }
                if (list.size() == 4) {
                    return DataResult.success(new Vector4f(list.get(0), list.get(1), list.get(2), list.get(3)));
                }
                return DataResult.error(() -> "color must be [r,g,b] or [r,g,b,a]");
            },
            color -> DataResult.success(List.of(color.x, color.y, color.z, color.w)));
}