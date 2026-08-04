package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

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
 * @param texture   the overlay texture path
 * @param layer     stacking order; higher values render on top. Ties are
 *                  broken by installation order (later installation wins)
 * @param alignment placement within the base canvas; only effective when
 *                  {@code fit} is {@link OverlayFit#NONE}
 * @param fit       how the overlay is fitted into the base canvas before
 *                  blending
 */
public record TextureOverlay(ResourceLocation texture, int layer, OverlayAlignment alignment, OverlayFit fit) {

    public static final Codec<TextureOverlay> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(TextureOverlay::texture),
                    Codec.INT.fieldOf("layer").forGetter(TextureOverlay::layer),
                    OverlayAlignment.CODEC.optionalFieldOf("alignment", OverlayAlignment.TOP_LEFT).forGetter(TextureOverlay::alignment),
                    OverlayFit.CODEC.optionalFieldOf("fit", OverlayFit.NONE).forGetter(TextureOverlay::fit)
            ).apply(instance, TextureOverlay::new)
    );
}
