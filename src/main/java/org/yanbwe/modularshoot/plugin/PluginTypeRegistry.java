package org.yanbwe.modularshoot.plugin;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Read-only query API for the {@code modularshoot:plugin_types} dynamic
 * registry.
 *
 * <p>The plugin type registry is a datapack-driven dynamic registry registered
 * via NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#PLUGIN_TYPES_KEY}). Its contents are populated
 * automatically from datapack JSONs located at
 * {@code data/<namespace>/modularshoot/plugin_types/<id>.json} when a world is
 * loaded and synced to clients on connect; it is <strong>empty on the main
 * menu</strong> (设计文档 §加载顺序). Every query method therefore takes a
 * {@link RegistryAccess} (or a {@link Level} that provides one) so the caller
 * supplies the correct runtime view.</p>
 *
 * <p>This class provides only read access. Java API registration into the
 * dynamic registry is handled by the DataPackRegistry mechanism during world
 * load; a full Java write API (a {@code register} method) is deferred to M6
 * (设计文档 §注册冲突与覆盖).</p>
 *
 * <p><strong>Note on empty tags:</strong> a plugin type registered with an
 * empty {@code tags} list cannot match any plugin and a {@code WARN} is logged
 * at registration time (设计文档 line 382). This validation is outside the
 * Codec's responsibility and is enforced by the registration layer, not by
 * this query API.</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class PluginTypeRegistry {
    private PluginTypeRegistry() {
    }

    /**
     * Looks up a plugin type definition by id in the
     * {@code modularshoot:plugin_types} registry.
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param pluginTypeId   the plugin type id, e.g.
     *                       {@code modularshoot:barrel}
     * @return the matching {@link PluginTypeDefinition}, or
     *         {@code Optional.empty()} when the registry is absent or the id is
     *         not registered
     */
    public static Optional<PluginTypeDefinition> getPluginType(RegistryAccess registryAccess, ResourceLocation pluginTypeId) {
        return registryAccess.registry(ModularShootRegistries.PLUGIN_TYPES_KEY)
                .flatMap(registry -> registry.getOptional(pluginTypeId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level        the world providing the {@link RegistryAccess}
     * @param pluginTypeId the plugin type id
     * @return the matching {@link PluginTypeDefinition}, or
     *         {@code Optional.empty()} when the id is not registered
     */
    public static Optional<PluginTypeDefinition> getPluginType(Level level, ResourceLocation pluginTypeId) {
        return getPluginType(level.registryAccess(), pluginTypeId);
    }

    /**
     * Returns every registered plugin type id in the
     * {@code modularshoot:plugin_types} registry.
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all plugin type ids; an empty set when the
     *         registry is absent (e.g. on the main menu)
     */
    public static Set<ResourceLocation> getAllPluginTypeIds(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.PLUGIN_TYPES_KEY)
                .map(Registry::keySet)
                .orElse(Set.of());
    }
}
