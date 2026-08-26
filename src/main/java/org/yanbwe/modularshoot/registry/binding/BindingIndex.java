package org.yanbwe.modularshoot.registry.binding;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;

/**
 * Shared dual-channel (Java API + datapack) binding index used by
 * {@link GunItemBindingRegistry} and {@link PluginItemBindingRegistry}
 * (审查 O3 — 两套逐行重复的索引实现收敛为一个泛型容器).
 *
 * <p>Query results are served from O(1) reverse indexes (bound item id &rarr;
 * winning binding): the Java-API channel maintains one incrementally
 * (rebuilding the affected item id on every registration, so re-registering
 * a key against a different item never leaves a stale reverse entry, 审查
 * R5), and the datapack channel builds a per-registry-instance index on
 * first query. Within each channel, multiple bindings for the same item id
 * resolve to the <b>lexicographically smallest entry key</b> (设计规格
 * 物品绑定系统 §3.2); across channels the Java-API binding wins.</p>
 *
 * @param <T> the binding record type ({@link GunItemBinding} or
 *            {@link PluginItemBinding})
 */
final class BindingIndex<T> {

    private final ResourceKey<Registry<T>> registryKey;
    private final Function<T, ResourceLocation> itemIdOf;

    /** Java-API store keyed by entry key (concurrent reads from hot paths). */
    private final Map<ResourceLocation, T> javaApiBindings = new ConcurrentHashMap<>();

    /** Reverse index: bound item id &rarr; winning Java-API entry key. */
    private final Map<ResourceLocation, ResourceLocation> javaApiItemIndex = new ConcurrentHashMap<>();

    /** Per-registry datapack reverse-index cache (weak keys survive reloads). */
    private final Map<Registry<T>, Map<ResourceLocation, T>> datapackIndexes =
            Collections.synchronizedMap(new WeakHashMap<>());

    BindingIndex(ResourceKey<Registry<T>> registryKey, Function<T, ResourceLocation> itemIdOf) {
        this.registryKey = registryKey;
        this.itemIdOf = itemIdOf;
    }

    /**
     * Registers a binding via the Java API, keeping the reverse index in
     * lock-step and marking the key with the registration coordinator so
     * datapack loads cannot override it (设计文档 §注册冲突与覆盖).
     *
     * @param key     the binding's entry key
     * @param binding the binding to register
     */
    void register(ResourceLocation key, T binding) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(binding, "binding");
        T previous = javaApiBindings.put(key, binding);
        // Rebuild the affected item ids from the current store instead of a
        // point-update: this also repairs the stale entry left behind when a
        // re-registration moves a key to a different item id (审查 R5).
        reindexItem(itemIdOf.apply(binding));
        if (previous != null && !itemIdOf.apply(previous).equals(itemIdOf.apply(binding))) {
            reindexItem(itemIdOf.apply(previous));
        }
        RegistrationCoordinator.markJavaApiRegistered(registryKey, key);
    }

    private void reindexItem(ResourceLocation itemId) {
        ResourceLocation winner = null;
        for (Map.Entry<ResourceLocation, T> entry : javaApiBindings.entrySet()) {
            if (itemIdOf.apply(entry.getValue()).equals(itemId)
                    && (winner == null || entry.getKey().compareTo(winner) < 0)) {
                winner = entry.getKey();
            }
        }
        if (winner == null) {
            javaApiItemIndex.remove(itemId);
        } else {
            javaApiItemIndex.put(itemId, winner);
        }
    }

    /** Returns an unmodifiable view of the Java-API store. */
    Map<ResourceLocation, T> javaApiBindings() {
        return Collections.unmodifiableMap(javaApiBindings);
    }

    /**
     * Resolves the winning binding for an item id across both channels
     * (Java API first, then the datapack registry).
     *
     * @param access the runtime registry view
     * @param itemId the bound item's registry id
     * @return the winning binding, or empty when no channel binds the item
     */
    Optional<T> resolveBinding(RegistryAccess access, ResourceLocation itemId) {
        ResourceLocation javaKey = javaApiItemIndex.get(itemId);
        if (javaKey != null) {
            T binding = javaApiBindings.get(javaKey);
            if (binding != null) {
                return Optional.of(binding);
            }
        }
        return access.registry(registryKey)
                .map(registry -> datapackIndex(registry).get(itemId));
    }

    private Map<ResourceLocation, T> datapackIndex(Registry<T> registry) {
        return datapackIndexes.computeIfAbsent(registry,
                r -> Map.copyOf(buildDatapackIndex(r.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().location(), Map.Entry::getValue)), itemIdOf)));
    }

    /**
     * Builds the reverse index {@code item id &rarr; winning binding} from a
     * plain entries map (entry key &rarr; binding). When multiple entries
     * bind the same item id, the lexicographically smallest key wins
     * (设计规格 物品绑定系统 §3.2). Pure function for unit testing.
     *
     * @param entries  the entries to index (entry key &rarr; binding)
     * @param itemIdOf binding &rarr; bound item id accessor
     * @param <T>      the binding type
     * @return a mutable map of item id &rarr; winning binding
     */
    static <T> Map<ResourceLocation, T> buildDatapackIndex(
            Map<ResourceLocation, T> entries, Function<T, ResourceLocation> itemIdOf) {
        Map<ResourceLocation, ResourceLocation> smallestKey = new HashMap<>();
        entries.forEach((key, binding) ->
                smallestKey.merge(itemIdOf.apply(binding), key,
                        (existing, incoming) -> incoming.compareTo(existing) < 0 ? incoming : existing));
        Map<ResourceLocation, T> index = new HashMap<>();
        smallestKey.forEach((itemId, key) -> index.put(itemId, entries.get(key)));
        return index;
    }

    /**
     * Finds the binding for an item id in a plain entries map; the
     * lexicographically smallest key wins (设计规格 §3.2). Pure function for
     * unit testing.
     *
     * @param entries  the entries to scan
     * @param itemId   the bound item's registry id
     * @param itemIdOf binding &rarr; bound item id accessor
     * @param <T>      the binding type
     * @return the winning binding, or empty when no entry binds the item id
     */
    static <T> Optional<T> findByItem(
            Map<ResourceLocation, T> entries, ResourceLocation itemId,
            Function<T, ResourceLocation> itemIdOf) {
        return entries.entrySet().stream()
                .filter(entry -> itemIdOf.apply(entry.getValue()).equals(itemId))
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue);
    }
}
