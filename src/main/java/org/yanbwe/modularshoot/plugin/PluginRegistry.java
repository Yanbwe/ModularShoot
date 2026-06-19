package org.yanbwe.modularshoot.plugin;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query and factory API for the {@code modularshoot:plugins} dynamic registry.
 *
 * <p>The plugin registry is a datapack-driven dynamic registry registered via
 * NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#PLUGINS_KEY}). Its contents are populated
 * automatically from datapack JSONs located at
 * {@code data/<namespace>/modularshoot/plugins/<id>.json} when a world is
 * loaded and synced to clients on connect; it is <strong>empty on the main
 * menu</strong> (设计文档 §加载顺序). Every query method therefore takes a
 * {@link RegistryAccess} (or a {@link Level} that provides one) so the caller
 * supplies the correct runtime view.</p>
 *
 * <p>This class provides only read access and {@link ItemStack} creation.
 * Java API registration into the dynamic registry is handled by the
 * DataPackRegistry mechanism during world load; a full Java write API (a
 * {@code register} method) is deferred to M6 (设计文档 §注册冲突与覆盖).</p>
 *
 * <p><strong>Note on empty tags:</strong> a plugin registered with an empty
 * {@code tags} list cannot match any category and therefore cannot be
 * installed on any gun; the registry loader emits a {@code WARN} for such
 * entries at registration time (设计文档 line 398). This validation is
 * outside the Codec's responsibility and is enforced by the registration
 * layer, not by this query API.</p>
 *
 * <p>The {@link #createPluginStack(ResourceLocation)} factory method does
 * <strong>not</strong> query the registry, so it is safe to call before a
 * world is loaded (e.g. for creative tabs).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class PluginRegistry {
    private PluginRegistry() {
    }

    /**
     * Looks up a plugin definition by id in the {@code modularshoot:plugins}
     * registry.
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param pluginId       the plugin definition id, e.g.
     *                       {@code modularshoot:rapid_barrel}
     * @return the matching {@link PluginDefinition}, or
     *         {@code Optional.empty()} when the registry is absent or the id is
     *         not registered
     */
    public static Optional<PluginDefinition> getPlugin(RegistryAccess registryAccess, ResourceLocation pluginId) {
        return registryAccess.registry(ModularShootRegistries.PLUGINS_KEY)
                .flatMap(registry -> registry.getOptional(pluginId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level    the world providing the {@link RegistryAccess}
     * @param pluginId the plugin definition id
     * @return the matching {@link PluginDefinition}, or
     *         {@code Optional.empty()} when the id is not registered
     */
    public static Optional<PluginDefinition> getPlugin(Level level, ResourceLocation pluginId) {
        return getPlugin(level.registryAccess(), pluginId);
    }

    /**
     * Returns every registered plugin id in the {@code modularshoot:plugins}
     * registry.
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all plugin ids; an empty set when the
     *         registry is absent (e.g. on the main menu)
     */
    public static Set<ResourceLocation> getAllPluginIds(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.PLUGINS_KEY)
                .map(Registry::keySet)
                .orElse(Set.of());
    }

    /**
     * Creates a plugin {@link ItemStack} for the given plugin id.
     *
     * <p>The stack is backed by the framework {@code modularshoot:plugin} item
     * and carries a {@link PluginData} component identifying the plugin
     * definition. No registry lookup is performed, so this is safe to call
     * before a world is loaded (e.g. for creative tabs).</p>
     *
     * @param pluginId the plugin definition id to bind to the stack
     * @return a new {@link ItemStack} with {@code plugin_data} set
     */
    public static ItemStack createPluginStack(ResourceLocation pluginId) {
        ItemStack stack = new ItemStack(ModularShootItems.PLUGIN_ITEM.get());
        stack.set(ModularShootDataComponents.PLUGIN_DATA.get(), new PluginData(pluginId));
        return stack;
    }
}
