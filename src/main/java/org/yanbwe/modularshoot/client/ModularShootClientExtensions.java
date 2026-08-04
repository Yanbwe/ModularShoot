package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.render.GunItemRenderer;
import org.yanbwe.modularshoot.client.render.PluginItemRenderer;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Client-side registration of the framework's custom item renderers.
 *
 * <p>In 1.21.1 the {@code custom_renderer} model flag no longer exists; the
 * supported way to route an item into a custom renderer is the
 * {@code builtin/entity} parent model (which bakes into a
 * {@code BuiltInModel} with {@code isCustomRenderer() == true}), combined with
 * an {@link IClientItemExtensions} registered on the mod event bus. Both the
 * gun item and the plugin item are routed here:
 * <ul>
 *   <li>{@code modularshoot:gun} → {@link GunItemRenderer} (shoot-texture
 *       switching + plugin overlay compositing)</li>
 *   <li>{@code modularshoot:plugin} → {@link PluginItemRenderer} (plugin
 *       {@code item_icon} texture)</li>
 * </ul>
 *
 * <p><b>Event ordering:</b> {@code RegisterClientExtensionsEvent} and
 * {@code RegisterClientReloadListenersEvent} are posted back-to-back from
 * {@code ClientHooks.initClientHooks} during the Minecraft constructor — the
 * extensions event first — so by the time the reload-listeners event fires
 * the renderer instances are guaranteed to exist. The renderers are also
 * {@code ResourceManagerReloadListener}s; registering them here clears their
 * dynamic texture caches on every resource reload (F3+T).</p>
 *
 * <p>This class is not instantiable; all handlers are static
 * {@code @SubscribeEvent} methods on the client mod bus, matching the
 * project's {@code @EventBusSubscriber} convention.</p>
 *
 * @see RegisterClientExtensionsEvent
 * @see RegisterClientReloadListenersEvent
 */
@EventBusSubscriber(modid = ModularShoot.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModularShootClientExtensions {

    private static GunItemRenderer gunRenderer;
    private static PluginItemRenderer pluginRenderer;

    private ModularShootClientExtensions() {
    }

    /**
     * Registers the framework item renderers as the client extensions for
     * the gun and plugin items.
     *
     * <p>Fired on the mod event bus during the Minecraft constructor; the
     * {@code Minecraft.getInstance()} singleton is already assigned at that
     * point (vanilla assigns it early in the constructor, before
     * {@code ClientHooks.initClientHooks} is called).</p>
     *
     * @param event the client extensions registration event
     */
    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        gunRenderer = new GunItemRenderer(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
        pluginRenderer = new PluginItemRenderer(minecraft.getBlockEntityRenderDispatcher(), minecraft.getEntityModels());
        event.registerItem(gunRenderer, ModularShootItems.GUN_ITEM.get());
        event.registerItem(pluginRenderer, ModularShootItems.PLUGIN_ITEM.get());
    }

    /**
     * Registers the item renderers as client resource-reload listeners so
     * their dynamic texture caches are cleared on every reload (F3+T),
     * forcing a re-read of the (possibly changed) PNGs from the resource
     * packs.
     *
     * @param event the client reload listeners registration event
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        if (gunRenderer != null) {
            event.registerReloadListener(gunRenderer);
        }
        if (pluginRenderer != null) {
            event.registerReloadListener(pluginRenderer);
        }
    }
}
