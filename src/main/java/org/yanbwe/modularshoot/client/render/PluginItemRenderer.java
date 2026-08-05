package org.yanbwe.modularshoot.client.render;

import java.util.List;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

/**
 * Custom item renderer for framework plugin items, drawing the plugin's
 * {@code item_icon} texture with the vanilla flat-item extrusion.
 *
 * <p>The plugin item is registered as {@code modularshoot:plugin} with the
 * shared {@code builtin/entity} model, so the vanilla pipeline delegates every
 * plugin stack to {@link #renderByItem}. This renderer resolves the
 * {@code plugin_data} component to the plugin definition id, looks up the
 * definition's {@link PluginDefinition#itemIcon()} texture path in the
 * {@code modularshoot:plugins} datapack registry, and draws it via
 * {@link DynamicGunTextureCache} + {@link DynamicItemModelRenderer}.</p>
 *
 * <p><b>Registry availability:</b> the plugins registry is datapack-driven and
 * empty on the main menu. When the definition cannot be resolved (no world
 * loaded, or the {@code pluginId} is missing from the registry), the item
 * falls back to the vanilla missing model via
 * {@link DynamicItemModelRenderer#renderMissing}.</p>
 *
 * <p><b>Client-only class.</b> References to {@link Minecraft} and other
 * client types restrict this class to the physical client. It is instantiated
 * and registered via {@code RegisterClientExtensionsEvent} on the client mod
 * event bus (see
 * {@link org.yanbwe.modularshoot.client.ModularShootClientExtensions}).</p>
 *
 * @see DynamicGunTextureCache
 * @see DynamicItemModelRenderer
 * @see org.yanbwe.modularshoot.client.ModularShootClientExtensions
 */
public final class PluginItemRenderer extends BlockEntityWithoutLevelRenderer implements IClientItemExtensions {

    /**
     * Constructs the renderer with the vanilla dispatcher and model set.
     *
     * @param dispatcher the block-entity render dispatcher
     * @param modelSet   the entity model set
     */
    public PluginItemRenderer(BlockEntityRenderDispatcher dispatcher, EntityModelSet modelSet) {
        super(dispatcher, modelSet);
    }

    /**
     * Returns this renderer as the item's client extension, routing every
     * plugin stack into {@link #renderByItem}.
     *
     * @return {@code this}
     */
    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return this;
    }

    /**
     * Clears the dynamic texture cache on resource reload (F3+T) so the
     * plugin icons are re-read from the resource packs.
     *
     * <p>The cache is the shared singleton
     * {@link DynamicGunTextureCache}, so this mirrors the gun renderer's
     * contract: each registered reload listener clears it (idempotent).</p>
     *
     * @param resourceManager the reloaded resource manager
     */
    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        DynamicGunTextureCache.getInstance().clear();
    }

    /**
     * Custom render entry point invoked by the vanilla pipeline for plugin
     * item stacks.
     *
     * <p>Resolves the plugin's {@code item_icon} texture and draws it as an
     * extruded flat item. Falls back to the missing model when the stack
     * carries no {@code plugin_data} or the definition is unavailable.</p>
     *
     * @param stack        the plugin item stack
     * @param context      the display context (first-person, GUI, ...)
     * @param poseStack    the pose stack
     * @param bufferSource the buffer source
     * @param light        the packed light value
     * @param overlay      the packed overlay value
     */
    @Override
    public void renderByItem(
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay) {

        ResourceLocation pluginId = ModularShootAPI.getPluginId(stack).orElse(null);
        Minecraft minecraft = Minecraft.getInstance();
        if (pluginId == null || minecraft.level == null) {
            DynamicItemModelRenderer.renderMissing(stack, context, poseStack, bufferSource, light, overlay);
            return;
        }

        PluginDefinition definition =
                ModularShootAPI.getPluginDefinition(minecraft.level.registryAccess(), pluginId).orElse(null);
        if (definition == null) {
            DynamicItemModelRenderer.renderMissing(stack, context, poseStack, bufferSource, light, overlay);
            return;
        }

        DynamicGunTextureCache.TextureHandle handle = DynamicGunTextureCache.getInstance().getOrCreate(
                new DynamicGunTextureCache.Key(definition.itemIcon(), List.of(), List.of(), 0));
        boolean auto = definition.textureScale() == TextureScaleMode.AUTO;
        DynamicItemModelRenderer.render(
                handle.location(),
                auto ? handle.width() / 16.0F : 1.0F,
                auto ? handle.height() / 16.0F : 1.0F,
                context, poseStack, bufferSource, light, overlay);
    }
}
