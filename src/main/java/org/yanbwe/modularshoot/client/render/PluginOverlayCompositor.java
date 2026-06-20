package org.yanbwe.modularshoot.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.plugin.TextureOverlay;

/**
 * Collects and sorts the texture overlays declared by a gun's installed
 * plugins, preparing them for 2D layer compositing by
 * {@link CompositeTextureBuilder}.
 *
 * <p>The design doc (§插件纹理叠加) specifies the render flow: collect every
 * installed plugin that declares a {@code texture_overlay}, sort by
 * {@code layer} from low to high, break ties by installation order (earlier
 * install first), then stack the overlays from bottom to top so a higher layer
 * &mdash; or a later install at the same layer &mdash; renders above lower
 * ones.</p>
 *
 * <p>This class performs only <em>collection and ordering</em>; it does not
 * touch {@code NativeImage} or any render-thread state. The returned list is
 * a pure data value that can be fed directly into
 * {@link CompositeTextureBuilder#composite} via
 * {@link #collectOverlayTextures}.</p>
 *
 * <p><b>Registry availability:</b> the {@code modularshoot:plugins} registry
 * is datapack-driven and <strong>empty on the main menu</strong>
 * (设计文档 §加载顺序). When {@code registryAccess} points at an unloaded
 * world, {@link PluginRegistry#getPlugin} returns {@code Optional.empty()}
 * and the offending plugin is silently skipped, yielding an empty overlay
 * list. Callers should supply
 * {@code Minecraft.getInstance().level.registryAccess()} during active
 * rendering (a world is always loaded by then).</p>
 *
 * @see TextureOverlay
 * @see CompositeTextureBuilder
 */
public final class PluginOverlayCompositor {

    private PluginOverlayCompositor() {
    }

    /**
     * Sorted overlay entry ready for compositing.
     *
     * @param texture      the overlay texture path
     * @param layer        stacking order; higher values render on top
     * @param installOrder zero-based index into the gun's
     *                     {@code installedPlugins} list; earlier installs have
     *                     smaller values and are composited first (below)
     */
    public record OverlayEntry(ResourceLocation texture, int layer, int installOrder) {
    }

    /**
     * Collects texture overlays from a gun's installed plugins, sorted by
     * layer (low to high) then by installation order (earlier first).
     *
     * <p>Plugins whose definition is absent from the registry (e.g. on the
     * main menu) or that declare no {@code texture_overlay} are skipped. The
     * returned list is ordered so that compositing it front-to-back places
     * higher layers and later installs on top, matching the design doc rule
     * "同层级按安装顺序，后装覆盖先装".</p>
     *
     * @param installedPlugins the gun's ordered plugin list
     *                         ({@link org.yanbwe.modularshoot.component.GunData#installedPlugins})
     * @param registryAccess   the runtime registry view (from a loaded world)
     * @return a new, sorted list of overlay entries; empty when no installed
     *         plugin declares a texture overlay
     */
    public static List<OverlayEntry> collectOverlays(
            List<PluginInstance> installedPlugins,
            RegistryAccess registryAccess) {

        List<OverlayEntry> entries = new ArrayList<>(installedPlugins.size());
        for (int i = 0; i < installedPlugins.size(); i++) {
            PluginInstance instance = installedPlugins.get(i);
            PluginDefinition definition = PluginRegistry.getPlugin(registryAccess, instance.pluginId()).orElse(null);
            if (definition == null) {
                continue;
            }
            if (definition.textureOverlay().isEmpty()) {
                continue;
            }
            TextureOverlay overlay = definition.textureOverlay().get();
            entries.add(new OverlayEntry(overlay.texture(), overlay.layer(), i));
        }
        entries.sort(Comparator
                .comparingInt(OverlayEntry::layer)
                .thenComparingInt(OverlayEntry::installOrder));
        return entries;
    }

    /**
     * Convenience overload that returns just the sorted texture paths, ready
     * to pass to
     * {@link CompositeTextureBuilder#composite(ResourceLocation, List)}.
     *
     * @param installedPlugins the gun's ordered plugin list
     * @param registryAccess   the runtime registry view
     * @return a new, sorted list of overlay texture paths; empty when no
     *         installed plugin declares a texture overlay
     */
    public static List<ResourceLocation> collectOverlayTextures(
            List<PluginInstance> installedPlugins,
            RegistryAccess registryAccess) {

        return collectOverlays(installedPlugins, registryAccess).stream()
                .map(OverlayEntry::texture)
                .toList();
    }
}
