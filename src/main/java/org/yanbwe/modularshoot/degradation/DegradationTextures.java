package org.yanbwe.modularshoot.degradation;

import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Fallback texture constants used when a gun or plugin definition is
 * missing and the framework must display a degraded icon
 * (设计文档 §枪械 gunId 失效降级).
 *
 * <p>These constants point to framework built-in placeholder textures.
 * When the texture file does not exist on disk, Minecraft automatically
 * renders the purple-and-black missing-texture square, which is an
 * acceptable degraded appearance (设计文档 §兜底纹理).</p>
 *
 * <p>This is a pure constant class; it is not instantiable.</p>
 */
public final class DegradationTextures {

    /**
     * Fallback texture for a gun whose {@code gunId} points to a
     * non-existent definition. Used by the client-side item model layer
     * to render a placeholder icon in inventories, drops, and item frames.
     */
    public static final ResourceLocation UNKNOWN_GUN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    ModularShoot.MODID, "textures/item/unknown_gun.png");

    /**
     * Fallback texture for a plugin item whose {@code pluginId} points to
     * a non-existent definition. Used by the client-side item model layer
     * to render a placeholder icon in inventories, drops, and item frames.
     */
    public static final ResourceLocation UNKNOWN_PLUGIN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    ModularShoot.MODID, "textures/item/unknown_plugin.png");

    private DegradationTextures() {
    }
}
