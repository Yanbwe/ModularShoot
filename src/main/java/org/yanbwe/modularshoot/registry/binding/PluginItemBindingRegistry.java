package org.yanbwe.modularshoot.registry.binding;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query and registration API for the {@code modularshoot:plugin_items}
 * dynamic registry — item→plugin bindings (设计规格 物品绑定系统 §3).
 *
 * <p>The plugin-items registry is a datapack-driven dynamic registry
 * registered via NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#PLUGIN_ITEMS_KEY}). Its contents are
 * populated from datapack JSONs when a world is loaded and synced to clients
 * on connect; it is <strong>empty on the main menu</strong>. Every query
 * method therefore takes a {@link RegistryAccess} so the caller supplies the
 * correct runtime view.</p>
 *
 * <p>This class supports two registration paths (设计规格 物品绑定系统 §3.3):
 * <ul>
 *   <li><b>Datapack JSON</b> &mdash; bindings loaded from
 *       {@code data/<namespace>/modularshoot/plugin_items/<id>.json} during
 *       world load or {@code /reload}. Handled automatically by NeoForge's
 *       {@code DataPackRegistryEvent}.</li>
 *   <li><b>Java API</b> &mdash; bindings registered programmatically via
 *       {@link #registerBinding} during mod initialisation. Java-API-
 *       registered keys are marked with the framework's
 *       {@link RegistrationCoordinator} so that later datapack loads cannot
 *       override them (设计文档 §注册冲突与覆盖).</li>
 * </ul>
 *
 * <p>Java-API-registered bindings are kept in an internal map and merged
 * with the datapack registry on every query; they survive {@code /reload}
 * because they are not part of the per-world datapack registry instance.
 * When the same item id is bound by both sources, the Java-API binding
 * wins. Within the datapack source, multiple bindings for the same item id
 * are resolved by <b>lexicographically smallest entry key</b> (设计规格
 * 物品绑定系统 §3.2).</p>
 *
 * <p>Query results are computed by linear scan of the (small) binding map;
 * no caching is performed. All methods are static utility methods; the
 * class is not instantiable.</p>
 */
public final class PluginItemBindingRegistry {

    /**
     * Internal store of bindings registered via the Java API.
     *
     * <p>Keyed by the binding's entry key. Uses {@link ConcurrentHashMap} so
     * that concurrent reads from query methods safely observe writes from
     * the mod-init thread (设计文档 §注册表并发策略).</p>
     */
    private static final Map<ResourceLocation, PluginItemBinding> JAVA_API_BINDINGS =
            new ConcurrentHashMap<>();

    private PluginItemBindingRegistry() {
    }

    // ---- Java API registration ------------------------------------------

    /**
     * Registers an item→plugin binding via the Java API.
     *
     * <p>Must be called during mod initialisation (before datapack loading
     * begins). The entry key is marked with
     * {@link RegistrationCoordinator#markJavaApiRegistered} so that any
     * later datapack JSON attempting to register the same key is rejected
     * (设计文档 §注册冲突与覆盖).</p>
     *
     * <p>Re-registering the same key replaces the previous binding and is
     * otherwise a no-op (the {@link RegistrationCoordinator} mark is
     * idempotent).</p>
     *
     * @param key     the binding's entry key, e.g. {@code mypack:light_binding};
     *                must not be {@code null}
     * @param binding the item→plugin binding; must not be {@code null}
     */
    public static void registerBinding(ResourceLocation key, PluginItemBinding binding) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(binding, "binding");
        JAVA_API_BINDINGS.put(key, binding);
        RegistrationCoordinator.markJavaApiRegistered(ModularShootRegistries.PLUGIN_ITEMS_KEY, key);
    }

    /**
     * Returns an unmodifiable view of all bindings registered via the Java
     * API.
     *
     * <p>This is a read-only view of the internal Java-API store; it does
     * <strong>not</strong> include datapack-registered entries. Intended for
     * testing and for internal framework logic that needs to distinguish
     * Java-API entries from datapack entries.</p>
     *
     * @return an unmodifiable map of Java-API-registered entry keys to
     *         bindings; empty when no bindings have been registered via the
     *         Java API
     */
    public static Map<ResourceLocation, PluginItemBinding> getJavaApiBindings() {
        return Collections.unmodifiableMap(JAVA_API_BINDINGS);
    }

    // ---- Query methods --------------------------------------------------

    /**
     * Resolves the plugin id bound to an item id (设计规格 物品绑定系统 §3.3).
     *
     * <p>Java-API-registered bindings (via {@link #registerBinding}) take
     * priority over datapack bindings for the same item id; the Java-API
     * store is scanned first and its first matching binding wins. When no
     * Java-API binding matches, the {@code modularshoot:plugin_items}
     * datapack registry is consulted; multiple datapack bindings for the
     * same item id are resolved by {@link #findByItem} (lexicographically
     * smallest entry key, 设计规格 §3.2).</p>
     *
     * @param access the runtime registry view (from a loaded world)
     * @param itemId the bound item's registry id, e.g. {@code minecraft:stick}
     * @return the bound plugin id, or {@code Optional.empty()} when the
     *         registry is absent and no Java-API binding matches the item
     */
    public static Optional<ResourceLocation> getBoundPluginId(
            RegistryAccess access, ResourceLocation itemId) {
        for (PluginItemBinding binding : JAVA_API_BINDINGS.values()) {
            if (binding.itemId().equals(itemId)) {
                return Optional.of(binding.pluginId());
            }
        }
        return access.registry(ModularShootRegistries.PLUGIN_ITEMS_KEY)
                .flatMap(registry -> findByItem(registry.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().location(), Map.Entry::getValue)), itemId))
                .map(PluginItemBinding::pluginId);
    }

    /**
     * Finds the binding for an item id in a plain entries map.
     *
     * <p>This is a package-private <strong>pure function</strong>: it has no
     * side effects and does not touch the registry or the Java-API store, so
     * it is directly unit-testable. When multiple entries bind the same item
     * id, the entry with the <b>lexicographically smallest key</b> wins
     * (deterministic conflict resolution, 设计规格 物品绑定系统 §3.2).</p>
     *
     * @param entries the entries to scan (typically a datapack registry's
     *                entry set converted to a map, keyed by entry key)
     * @param itemId  the bound item's registry id
     * @return the winning binding, or {@code Optional.empty()} when no entry
     *         binds the item id
     */
    static Optional<PluginItemBinding> findByItem(
            Map<ResourceLocation, PluginItemBinding> entries, ResourceLocation itemId) {
        return entries.entrySet().stream()
                .filter(entry -> entry.getValue().itemId().equals(itemId))
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue);
    }
}
