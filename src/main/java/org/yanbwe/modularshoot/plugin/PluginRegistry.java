package org.yanbwe.modularshoot.plugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.RegistryLookupCache;

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
 * <p>This class provides read access, {@link ItemStack} creation, and two
 * Java registration channels symmetric to the gun-side
 * {@link org.yanbwe.modularshoot.registry.gun.GunRegistry} (审查 E1 — the
 * former "deferred to M6" write API): {@link #registerPlugin} for static
 * definitions registered at mod initialisation, and
 * {@link #registerPluginDefinitionProvider} for definitions computed at
 * query time (programmatic loot affixes etc.).</p>
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
     * Internal store of plugin definitions registered via the Java API
     * ({@link #registerPlugin}, 审查 E1). Keyed by plugin id; merged with
     * the datapack registry on every query, with Java-API entries taking
     * priority (与枪械侧 JAVA_API_GUNS 语义一致).
     */
    private static final Map<ResourceLocation, PluginDefinition> JAVA_API_PLUGINS =
            new ConcurrentHashMap<>();

    /**
     * Registered dynamic definition providers, queried in registration
     * order between the Java API map and the datapack registry (审查 E1,
     * mirrors {@code GunRegistry#DEFINITION_PROVIDERS}).
     */
    private static final List<PluginDefinitionProvider> DEFINITION_PROVIDERS =
            new CopyOnWriteArrayList<>();

    /** Per-{@link net.minecraft.core.Registry} weak-reference lookup cache. */
    private static final RegistryLookupCache<PluginDefinition> LOOKUP_CACHE =
            new RegistryLookupCache<>();

    // ---- Java API registration (审查 E1) ------------------------------

    /**
     * Registers a plugin definition via the Java API (审查 E1).
     *
     * <p>Must be called during mod initialisation (before datapack loading
     * begins). The registered id is marked with
     * {@link RegistrationCoordinator#markJavaApiRegistered} so that any
     * later datapack JSON attempting to register the same id is rejected
     * with a {@code WARN} (设计文档 §注册冲突与覆盖).</p>
     *
     * <p>Java-API-registered entries are kept in an internal map and merged
     * with the datapack registry on every query ({@link #getPlugin},
     * {@link #getAllPluginIds}). They survive {@code /reload} because they
     * are not part of the per-world datapack registry instance. Re-registering
     * the same id replaces the previous definition (the coordinator mark is
     * idempotent).</p>
     *
     * @param pluginId   the plugin definition id, e.g.
     *                   {@code mypack:rapid_affix}; must not be {@code null}
     * @param definition the plugin definition; must not be {@code null}
     */
    public static void registerPlugin(ResourceLocation pluginId, PluginDefinition definition) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(definition, "definition");
        JAVA_API_PLUGINS.put(pluginId, definition);
        RegistrationCoordinator.markJavaApiRegistered(ModularShootRegistries.PLUGINS_KEY, pluginId);
    }

    /**
     * Registers a dynamic plugin definition provider (审查 E1, mirrors
     * {@code GunRegistry#registerGunDefinitionProvider}).
     *
     * <p>The provider is queried by {@link #getPlugin} in registration
     * order, after the Java API map and before the datapack registry.
     * Return empty from the provider to fall through to the next source.
     * Registration is process-wide and survives {@code /reload}.</p>
     *
     * @param provider the provider to register; must not be {@code null}
     */
    public static void registerPluginDefinitionProvider(PluginDefinitionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        DEFINITION_PROVIDERS.add(provider);
    }

    /**
     * Returns an unmodifiable snapshot of all registered plugin definition
     * providers (read-only view for testing and debugging).
     *
     * @return an unmodifiable list of the registered providers; empty when
     *         none have been registered
     */
    public static List<PluginDefinitionProvider> getPluginDefinitionProviders() {
        return List.copyOf(DEFINITION_PROVIDERS);
    }

    /**
     * Returns an unmodifiable snapshot of all plugin definitions registered
     * via the Java API (does <strong>not</strong> include datapack entries).
     *
     * @return an unmodifiable map of Java-API-registered plugin ids to
     *         definitions
     */
    public static Map<ResourceLocation, PluginDefinition> getJavaApiRegisteredPlugins() {
        return Collections.unmodifiableMap(JAVA_API_PLUGINS);
    }

    /**
     * Looks up a plugin definition by id in the {@code modularshoot:plugins}
     * registry.
     *
     * <p>Source order (审查 E1, mirrors {@code GunRegistry#getGun}):
     * Java-API-registered entries first, then dynamic providers in
     * registration order, then the datapack registry. When multiple sources
     * match the same id the Java-API definition wins, then the first
     * non-empty provider result; a provider result shadows the datapack
     * entry without warning.</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param pluginId       the plugin definition id, e.g.
     *                       {@code modularshoot:rapid_barrel}
     * @return the matching {@link PluginDefinition}, or
     *         {@code Optional.empty()} when no source knows the id
     */
    public static Optional<PluginDefinition> getPlugin(RegistryAccess registryAccess, ResourceLocation pluginId) {
        final PluginDefinition javaApiPlugin = JAVA_API_PLUGINS.get(pluginId);
        if (javaApiPlugin != null) {
            return Optional.of(javaApiPlugin);
        }
        for (PluginDefinitionProvider provider : DEFINITION_PROVIDERS) {
            final Optional<PluginDefinition> provided = queryProvider(provider, pluginId);
            if (provided.isPresent()) {
                return provided;
            }
        }
        return LOOKUP_CACHE.get(registryAccess, ModularShootRegistries.PLUGINS_KEY, pluginId);
    }

    /**
     * Queries a single provider defensively: {@code null} is treated as
     * empty and any thrown exception is caught, logged as a WARN and
     * swallowed — a faulty provider must not crash the install/tooltip hot
     * paths (同 {@code GunRegistry#queryProvider} 降级哲学).
     */
    private static Optional<PluginDefinition> queryProvider(
            PluginDefinitionProvider provider, ResourceLocation pluginId) {
        try {
            Optional<PluginDefinition> provided = provider.get(pluginId);
            return provided == null ? Optional.empty() : provided;
        } catch (Exception e) {
            ModularShoot.LOGGER.warn(
                    "Plugin definition provider {} threw an exception for {}; falling back to the next source",
                    provider.getClass().getName(), pluginId, e);
            return Optional.empty();
        }
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
     * Returns every registered plugin id.
     *
     * <p>The returned set is the union of datapack-registered ids and
     * Java-API-registered ids ({@link #registerPlugin}); duplicate ids appear
     * once (Java-API wins at query time). Provider-defined ids are
     * <strong>not</strong> enumerated: they are computed at query time and
     * may not exist as stable ids (与枪械侧 getAllGunIds 一致).</p>
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all plugin ids; an empty set when the
     *         registry is absent (e.g. on the main menu) and no Java-API
     *         entries have been registered
     */
    public static Set<ResourceLocation> getAllPluginIds(RegistryAccess registryAccess) {
        Set<ResourceLocation> ids = new LinkedHashSet<>(
                LOOKUP_CACHE.getAllIds(registryAccess, ModularShootRegistries.PLUGINS_KEY));
        ids.addAll(JAVA_API_PLUGINS.keySet());
        return Collections.unmodifiableSet(ids);
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
