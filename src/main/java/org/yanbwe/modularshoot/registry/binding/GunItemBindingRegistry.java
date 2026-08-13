package org.yanbwe.modularshoot.registry.binding;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query and registration API for the {@code modularshoot:gun_items} dynamic
 * registry — item→gun bindings (设计规格 物品绑定系统 §3).
 *
 * <p>The gun-items registry is a datapack-driven dynamic registry registered
 * via NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#GUN_ITEMS_KEY}). Its contents are populated
 * from datapack JSONs when a world is loaded and synced to clients on connect;
 * it is <strong>empty on the main menu</strong>. Every query method therefore
 * takes a {@link RegistryAccess} so the caller supplies the correct runtime
 * view.</p>
 *
 * <p>This class supports two registration paths (设计规格 物品绑定系统 §3.3):
 * <ul>
 *   <li><b>Datapack JSON</b> &mdash; bindings loaded from
 *       {@code data/<namespace>/modularshoot/gun_items/<id>.json} during
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
 * <p>Query results are served from O(1) reverse indexes (item id &rarr;
 * winning binding): the Java-API channel maintains one incrementally, and
 * the datapack channel builds a per-registry-instance index on first query
 * (审查优化: 消除每调用重建整表). All methods are static utility methods;
 * the class is not instantiable.</p>
 */
public final class GunItemBindingRegistry {

    /**
     * Internal store of bindings registered via the Java API.
     *
     * <p>Keyed by the binding's entry key. Uses {@link ConcurrentHashMap} so
     * that concurrent reads from query methods safely observe writes from
     * the mod-init thread (设计文档 §注册表并发策略).</p>
     */
    private static final Map<ResourceLocation, GunItemBinding> JAVA_API_BINDINGS =
            new ConcurrentHashMap<>();

    /**
     * Reverse index: bound item id &rarr; entry key of the lexicographically
     * smallest Java-API binding for that item (审查优化: 反向索引). Kept in
     * lock-step with {@link #JAVA_API_BINDINGS} by {@link #registerBinding};
     * querying is O(1) instead of the previous per-call linear scan of the
     * whole Java-API store.
     */
    private static final Map<ResourceLocation, ResourceLocation> JAVA_API_ITEM_INDEX =
            new ConcurrentHashMap<>();

    /**
     * Per-registry reverse index cache for the datapack channel: registry
     * instance &rarr; (bound item id &rarr; winning binding). Weak keys let an
     * unloaded world's registry be garbage-collected; a {@code /reload} swaps
     * the registry instance, so the stale index is naturally discarded and
     * rebuilt on first query. Each index is built once per registry instance
     * (O(B) at first query) and serves every subsequent lookup in O(1) — the
     * hot paths ({@code BoundGunAttachHandler} per-tick inventory scan, client
     * per-frame {@code isGun}) previously rebuilt the whole map per call.
     */
    private static final Map<Registry<GunItemBinding>, Map<ResourceLocation, GunItemBinding>> DATAPACK_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GunItemBindingRegistry() {
    }

    // ---- Java API registration ------------------------------------------

    /**
     * Registers an item→gun binding via the Java API.
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
     * @param key     the binding's entry key, e.g. {@code mypack:rifle_binding};
     *                must not be {@code null}
     * @param binding the item→gun binding; must not be {@code null}
     */
    public static void registerBinding(ResourceLocation key, GunItemBinding binding) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(binding, "binding");
        JAVA_API_BINDINGS.put(key, binding);
        // Keep the reverse index in lock-step: the lexicographically smallest
        // entry key wins for a given item id (确定性冲突消解，与 datapack 侧一致).
        JAVA_API_ITEM_INDEX.compute(binding.itemId(), (itemId, existingKey) ->
                existingKey == null || key.compareTo(existingKey) < 0 ? key : existingKey);
        RegistrationCoordinator.markJavaApiRegistered(ModularShootRegistries.GUN_ITEMS_KEY, key);
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
    public static Map<ResourceLocation, GunItemBinding> getJavaApiBindings() {
        return Collections.unmodifiableMap(JAVA_API_BINDINGS);
    }

    // ---- Query methods --------------------------------------------------

    /**
     * Resolves the gun id bound to an item id (设计规格 物品绑定系统 §3.3).
     *
     * <p>Java-API-registered bindings (via {@link #registerBinding}) take
     * priority over datapack bindings for the same item id; the Java-API
     * store is scanned first and its first matching binding wins. When no
     * Java-API binding matches, the {@code modularshoot:gun_items} datapack
     * registry is consulted; multiple datapack bindings for the same item
     * id are resolved by {@link #findByItem} (lexicographically smallest
     * entry key, 设计规格 §3.2).</p>
     *
     * @param access the runtime registry view (from a loaded world)
     * @param itemId the bound item's registry id, e.g.
     *               {@code minecraft:diamond_sword}
     * @return the bound gun id, or {@code Optional.empty()} when the
     *         registry is absent and no Java-API binding matches the item
     */
    public static Optional<ResourceLocation> getBoundGunId(
            RegistryAccess access, ResourceLocation itemId) {
        // Java-API channel: O(1) reverse-index lookup.
        ResourceLocation javaKey = JAVA_API_ITEM_INDEX.get(itemId);
        if (javaKey != null) {
            GunItemBinding binding = JAVA_API_BINDINGS.get(javaKey);
            if (binding != null) {
                return Optional.of(binding.gunId());
            }
        }
        // Datapack channel: per-registry cached reverse index (built once per
        // registry instance, then O(1) per query).
        return access.registry(ModularShootRegistries.GUN_ITEMS_KEY)
                .flatMap(registry -> Optional.ofNullable(datapackIndex(registry).get(itemId))
                        .map(GunItemBinding::gunId));
    }

    /**
     * Returns the cached reverse index of a datapack binding registry,
     * building it on first access for that registry instance (审查优化:
     * 每调用重建整表 → 每注册表一次构建 + O(1) 查询).
     *
     * @param registry the datapack binding registry instance
     * @return an immutable view of item id &rarr; winning binding
     */
    private static Map<ResourceLocation, GunItemBinding> datapackIndex(Registry<GunItemBinding> registry) {
        return DATAPACK_INDEXES.computeIfAbsent(registry,
                r -> Map.copyOf(buildDatapackIndex(r.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().location(), Map.Entry::getValue)))));
    }

    /**
     * Builds the reverse index {@code item id &rarr; winning binding} from a
     * plain entries map (entry key &rarr; binding).
     *
     * <p>When multiple entries bind the same item id, the entry with the
     * <b>lexicographically smallest key</b> wins — the same deterministic
     * conflict resolution as {@link #findByItem} (设计规格 物品绑定系统 §3.2),
     * in a single pass with no per-query stream allocation. Package-private
     * pure function for direct unit testing.</p>
     *
     * @param entries the entries to index (entry key &rarr; binding)
     * @return a mutable map of item id &rarr; winning binding
     */
    static Map<ResourceLocation, GunItemBinding> buildDatapackIndex(
            Map<ResourceLocation, GunItemBinding> entries) {
        Map<ResourceLocation, ResourceLocation> smallestKey = new HashMap<>();
        entries.forEach((key, binding) ->
                smallestKey.merge(binding.itemId(), key,
                        (existing, incoming) -> incoming.compareTo(existing) < 0 ? incoming : existing));
        Map<ResourceLocation, GunItemBinding> index = new HashMap<>();
        smallestKey.forEach((itemId, key) -> index.put(itemId, entries.get(key)));
        return index;
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
    static Optional<GunItemBinding> findByItem(
            Map<ResourceLocation, GunItemBinding> entries, ResourceLocation itemId) {
        return entries.entrySet().stream()
                .filter(entry -> entry.getValue().itemId().equals(itemId))
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue);
    }
}
