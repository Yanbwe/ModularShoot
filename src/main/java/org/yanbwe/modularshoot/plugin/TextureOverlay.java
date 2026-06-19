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
 * @param texture the overlay texture path
 * @param layer   stacking order; higher values render on top. Ties are broken
 *                by installation order (later installation wins)
 */
public record TextureOverlay(ResourceLocation texture, int layer) {

    public static final Codec<TextureOverlay> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(TextureOverlay::texture),
                    Codec.INT.fieldOf("layer").forGetter(TextureOverlay::layer)
            ).apply(instance, TextureOverlay::new)
    );
}
