package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector4f;

/**
 * Texture overlay stacked on top of a gun's base texture by an installed
 * plugin.
 *
 * <p>Overlays are drawn according to their {@code layer}: a higher layer
 * renders above a lower one. When two overlays share the same layer the
 * installation order breaks the tie &mdash; a plugin installed later is drawn
 * on top of one installed earlier.</p>
 *
 * <p>{@code alignment} and {@code fit} control how the overlay is placed and
 * sized relative to the base texture canvas (see {@link OverlayAlignment} /
 * {@link OverlayFit}): with the defaults ({@code top_left} + {@code none})
 * the overlay is blended pixel-for-pixel at the top-left corner, clipped at
 * the canvas edges &mdash; the legacy behaviour. Overlays smaller than the
 * base can be centred (or placed at any grid point), and can be resampled to
 * cover the whole canvas ({@code fill}) or fit inside it without distortion
 * ({@code contain}).</p>
 *
 * <p>{@code tint}, {@code blend} and {@code outline} control how the overlay
 * combines with the layers beneath it: {@code tint} multiplies the overlay's
 * RGBA pixels channel-by-channel before blending (white is the identity),
 * {@code blend} selects the colour mixing mode against the underlying layers
 * (only colour channels are affected; alpha always uses ordinary over
 * compositing) and {@code outline} paints a stroke along the overlay's own
 * alpha silhouette. The stroke is applied after the tint, so it is never
 * tinted.</p>
 *
 * @param texture   the overlay texture path
 * @param layer     stacking order; higher values render on top. Ties are
 *                  broken by installation order (later installation wins)
 * @param alignment placement within the base canvas; only effective when
 *                  {@code fit} is {@link OverlayFit#NONE}
 * @param fit       how the overlay is fitted into the base canvas before
 *                  blending
 * @param tint      per-channel RGBA multiplier applied to the overlay pixels
 *                  before blending; defaults to white (1,1,1,1), the identity
 * @param blend     colour mixing mode between this overlay and the underlying
 *                  layers; defaults to {@link OverlayBlend#NORMAL} (the
 *                  legacy behaviour)
 * @param outline   optional stroke painted along this overlay's alpha edge;
 *                  empty when no outline is drawn
 */
public record TextureOverlay(ResourceLocation texture, int layer, OverlayAlignment alignment, OverlayFit fit,
                             Vector4f tint, OverlayBlend blend, Optional<OutlineSpec> outline) {

    public static final Codec<TextureOverlay> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(TextureOverlay::texture),
                    Codec.INT.fieldOf("layer").forGetter(TextureOverlay::layer),
                    OverlayAlignment.CODEC.optionalFieldOf("alignment", OverlayAlignment.TOP_LEFT).forGetter(TextureOverlay::alignment),
                    OverlayFit.CODEC.optionalFieldOf("fit", OverlayFit.NONE).forGetter(TextureOverlay::fit),
                    ExtraCodecs.VECTOR4F.optionalFieldOf("tint", new Vector4f(1.0F, 1.0F, 1.0F, 1.0F)).forGetter(TextureOverlay::tint),
                    OverlayBlend.CODEC.optionalFieldOf("blend", OverlayBlend.NORMAL).forGetter(TextureOverlay::blend),
                    OutlineSpec.CODEC.optionalFieldOf("outline").forGetter(TextureOverlay::outline)
            ).apply(instance, TextureOverlay::new)
    );
}
