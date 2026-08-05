package org.yanbwe.modularshoot.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;
import org.yanbwe.modularshoot.plugin.OutlineSpec;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayBlend;
import org.yanbwe.modularshoot.plugin.OverlayFit;
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
 * <p>Each collected {@link CompositeTextureBuilder.OverlayLayer} carries the
 * plugin's placement ({@link OverlayAlignment}), sizing ({@link OverlayFit}),
 * colour tint, blend mode and optional outline parameters, so compositing is
 * driven by the full definition rather than the bare texture path.</p>
 *
 * <p>This class performs only <em>collection and ordering</em>; it does not
 * touch {@code NativeImage} or any render-thread state. The returned list is
 * a pure data value that can be fed directly into
 * {@link CompositeTextureBuilder#composite} via
 * {@link #collectOverlayLayers}.</p>
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
     * @param alignment    placement within the base canvas
     * @param fit          sizing relative to the base canvas
     * @param tint         per-channel RGBA multiplier applied before blending
     * @param blend        colour mixing mode against the layers beneath
     * @param outline      optional outline stroke painted after tinting
     */
    public record OverlayEntry(
            ResourceLocation texture,
            int layer,
            int installOrder,
            OverlayAlignment alignment,
            OverlayFit fit,
            Vector4f tint,
            OverlayBlend blend,
            Optional<OutlineSpec> outline) {
    }

    /**
     * Render inputs collected from a gun's installed plugins: the sorted overlay
     * layers and the whole-gun outlines (in installation order).
     *
     * @param overlays    the sorted overlay layers, bottom-to-top
     * @param gunOutlines the whole-gun outline specs, in installation order;
     *                    sorting by width is the compositor's responsibility
     */
    public record OverlayRenderData(List<CompositeTextureBuilder.OverlayLayer> overlays, List<OutlineSpec> gunOutlines) {
    }

    /**
     * Collects overlay layers from a gun's installed plugins, sorted by
     * layer (low to high) then by installation order (earlier first).
     *
     * <p>This is a compatibility wrapper delegating to
     * {@link #collectRenderData} and returning only its {@code overlays}
     * part; callers that also need the gun's whole-gun outlines should use
     * {@link #collectRenderData} directly.</p>
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
     * @return a new, sorted list of overlay layers; empty when no installed
     *         plugin declares a texture overlay
     */
    public static List<CompositeTextureBuilder.OverlayLayer> collectOverlayLayers(
            List<PluginInstance> installedPlugins,
            RegistryAccess registryAccess) {
        return collectRenderData(installedPlugins, registryAccess).overlays();
    }

    /**
     * Sync-data overload of {@link #collectOverlayLayers}, accepting the wire
     * format {@link GunSyncS2CPacket.PluginSyncEntry} list pushed by the
     * server and stored in
     * {@link org.yanbwe.modularshoot.client.ClientGunDataStore}.
     *
     * <p>This is a compatibility wrapper delegating to
     * {@link #collectRenderDataFromSync} and returning only its
     * {@code overlays} part.</p>
     *
     * <p>Behaviour is identical to the {@link PluginInstance} overload —
     * only the {@code pluginId} of each entry is read, so the two record
     * types are interchangeable here. A separate method name is required
     * because {@code List<PluginInstance>} and
     * {@code List<PluginSyncEntry>} share the same erasure and cannot
     * overload each other.</p>
     *
     * @param installedPlugins the synced plugin entry list
     * @param registryAccess   the runtime registry view (from a loaded world)
     * @return a new, sorted list of overlay layers; empty when no installed
     *         plugin declares a texture overlay
     */
    public static List<CompositeTextureBuilder.OverlayLayer> collectOverlayLayersFromSync(
            List<GunSyncS2CPacket.PluginSyncEntry> installedPlugins,
            RegistryAccess registryAccess) {
        return collectRenderDataFromSync(installedPlugins, registryAccess).overlays();
    }

    /**
     * Collects the render inputs of a gun's installed plugins: the sorted
     * overlay layers (layer low to high, then installation order) and the
     * whole-gun outlines in installation order.
     *
     * <p>Plugins whose definition is absent from the registry (e.g. on the
     * main menu) are skipped and contribute neither overlays nor outlines.
     * The overlay list is ordered so that compositing it front-to-back places
     * higher layers and later installs on top, matching the design doc rule
     * "同层级按安装顺序，后装覆盖先装". {@code gunOutlines} keeps the
     * installation order (earlier installs first); sorting by width is the
     * compositor's responsibility
     * ({@link CompositeTextureBuilder#composite}).</p>
     *
     * @param installedPlugins the gun's ordered plugin list
     *                         ({@link org.yanbwe.modularshoot.component.GunData#installedPlugins})
     * @param registryAccess   the runtime registry view (from a loaded world)
     * @return the collected render inputs; both lists may be empty
     */
    public static OverlayRenderData collectRenderData(
            List<PluginInstance> installedPlugins,
            RegistryAccess registryAccess) {
        List<ResourceLocation> pluginIds = new ArrayList<>(installedPlugins.size());
        for (PluginInstance instance : installedPlugins) {
            pluginIds.add(instance.pluginId());
        }
        return collectRenderDataByIds(pluginIds, registryAccess);
    }

    /**
     * Sync-data counterpart of {@link #collectRenderData}, accepting the
     * wire format {@link GunSyncS2CPacket.PluginSyncEntry} list pushed by
     * the server and stored in
     * {@link org.yanbwe.modularshoot.client.ClientGunDataStore}.
     *
     * <p>Behaviour is identical to the {@link PluginInstance} overload —
     * only the {@code pluginId} of each entry is read, so the two record
     * types are interchangeable here. A separate method name is required
     * because {@code List<PluginInstance>} and
     * {@code List<PluginSyncEntry>} share the same erasure and cannot
     * overload each other.</p>
     *
     * @param installedPlugins the synced plugin entry list
     * @param registryAccess   the runtime registry view (from a loaded world)
     * @return the collected render inputs; both lists may be empty
     */
    public static OverlayRenderData collectRenderDataFromSync(
            List<GunSyncS2CPacket.PluginSyncEntry> installedPlugins,
            RegistryAccess registryAccess) {
        List<ResourceLocation> pluginIds = new ArrayList<>(installedPlugins.size());
        for (GunSyncS2CPacket.PluginSyncEntry entry : installedPlugins) {
            pluginIds.add(entry.pluginId());
        }
        return collectRenderDataByIds(pluginIds, registryAccess);
    }

    /**
     * Shared core that collects the render inputs (sorted overlay layers
     * plus installation-ordered gun outlines) from a plain list of plugin
     * ids, using the list index as the installation order.
     *
     * <p>Plugins whose definition is absent from the registry (e.g. on the
     * main menu) are skipped and contribute neither overlays nor outlines.
     * The overlay list is ordered so that compositing it front-to-back places
     * higher layers and later installs on top, matching the design doc rule
     * "同层级按安装顺序，后装覆盖先装". {@code gunOutlines} keeps the
     * installation order (earlier installs first); sorting by width is the
     * compositor's responsibility
     * ({@link CompositeTextureBuilder#composite}).</p>
     *
     * @param pluginIds      the ordered plugin definition ids
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return the collected render inputs; both lists may be empty
     */
    private static OverlayRenderData collectRenderDataByIds(
            List<ResourceLocation> pluginIds,
            RegistryAccess registryAccess) {
        List<OverlayEntry> entries = new ArrayList<>(pluginIds.size());
        List<OutlineSpec> gunOutlines = new ArrayList<>();
        for (int i = 0; i < pluginIds.size(); i++) {
            PluginDefinition definition = PluginRegistry.getPlugin(registryAccess, pluginIds.get(i)).orElse(null);
            if (definition == null) {
                continue;
            }
            if (definition.textureOverlay().isPresent()) {
                TextureOverlay overlay = definition.textureOverlay().get();
                entries.add(new OverlayEntry(
                        overlay.texture(),
                        overlay.layer(),
                        i,
                        overlay.alignment(),
                        overlay.fit(),
                        overlay.tint(),
                        overlay.blend(),
                        overlay.outline()));
            }
            definition.gunOutline().ifPresent(gunOutlines::add);
        }
        entries.sort(Comparator
                .comparingInt(OverlayEntry::layer)
                .thenComparingInt(OverlayEntry::installOrder));
        return new OverlayRenderData(
                entries.stream()
                        .map(e -> new CompositeTextureBuilder.OverlayLayer(
                                e.texture(), e.alignment(), e.fit(), e.tint(), e.blend(), e.outline()))
                        .toList(),
                gunOutlines);
    }
}
