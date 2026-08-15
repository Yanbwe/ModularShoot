package org.yanbwe.modularshoot.datapack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * One reload's shared view over the reloaded {@link RegistryAccess}.
 *
 * <p>Before this class existed, {@link DatapackReloadListener} re-materialised
 * the same dynamic registry several times per {@code /reload}: once for the
 * per-registry summary loader, again inside {@link CrossReferenceValidator}
 * (which re-derived each table's keyset), and a third time for the
 * registration-conflict check. Each pass called {@code collectEntries}/
 * {@code registryKeys}, i.e. {@code entrySet()}/{@code keySet()} on the same
 * registry, doing wasteful full traversals on a per-reload hot path that is
 * low-frequency but can be perceptibly blocking with large datapacks.</p>
 *
 * <p>This snapshot is the single point of truth for one reload: consumers
 * (the summary loaders, {@link CrossReferenceValidator} and the conflict
 * check) share the same memoised entries map per registry, so each registry
 * is traversed exactly once. {@link #keys(ResourceKey)} is derived from
 * {@link #entries(ResourceKey)} rather than calling {@code keySet()} again,
 * guaranteeing a single traversal even when a consumer only needs the ids.
 * {@link #allPluginTypeTags()} additionally memoises the flattened
 * {@code plugin_types} tag union so tag matching does not rebuild it per
 * plugin.</p>
 *
 * <p>The snapshot is intentionally scoped to a single reload (created in
 * {@code DatapackReloadListener}'s reload entry and dropped afterwards), so
 * it needs no weak references or eviction: a fresh instance is built whenever
 * the registry instances change, and stale instances are simply garbage
 * collected.</p>
 */
final class ReloadSharedEntries {

    private final RegistryAccess access;

    /** Memoised per-registry entries maps, keyed by {@link ResourceKey}. */
    private final Map<ResourceKey<?>, Object> entriesCache = new HashMap<>();

    private Set<String> allPluginTypeTags;
    private boolean allPluginTypeTagsComputed;
    private int pluginTypeTagsFlattenCount;

    /**
     * @param access the reloaded registry access (all registries loaded and frozen)
     */
    ReloadSharedEntries(RegistryAccess access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    /**
     * Returns the underlying {@link RegistryAccess} for consumers that need a
     * registry the snapshot does not special-case (e.g. the vanilla
     * {@code DAMAGE_TYPE} registry).
     *
     * @return the wrapped registry access
     */
    RegistryAccess access() {
        return access;
    }

    /**
     * Returns the reloaded entries of a framework registry as an unmodifiable
     * map keyed by id, collecting it at most once per reload.
     *
     * @param key the registry key
     * @param <T> the registry value type
     * @return an unmodifiable map of id to entry; empty when the registry is absent
     */
    <T> Map<ResourceLocation, T> entries(ResourceKey<Registry<T>> key) {
        Objects.requireNonNull(key, "key");
        @SuppressWarnings("unchecked")
        Map<ResourceLocation, T> cached = (Map<ResourceLocation, T>) entriesCache.get(key);
        if (cached == null) {
            cached = access.registry(key)
                    .map(reg -> reg.entrySet().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    e -> e.getKey().location(),
                                    Map.Entry::getValue)))
                    .orElse(Map.of());
            entriesCache.put(key, cached);
        }
        return cached;
    }

    /**
     * Returns the id set of a framework registry, derived from the single
     * memoised {@link #entries(ResourceKey)} collection so no second registry
     * traversal occurs.
     *
     * @param key the registry key
     * @param <T> the registry value type
     * @return the registry's id set, or an empty set when the registry is absent
     */
    <T> Set<ResourceLocation> keys(ResourceKey<Registry<T>> key) {
        return entries(key).keySet();
    }

    /**
     * Returns the flattened union of every {@code plugin_types} entry's tag
     * set (as strings), computed once per reload and reused by
     * {@link CrossReferenceValidator} for every plugin.
     *
     * @return the union of all plugin-type tags; empty when the table is absent
     */
    Set<String> allPluginTypeTags() {
        if (!allPluginTypeTagsComputed) {
            allPluginTypeTags = entries(ModularShootRegistries.PLUGIN_TYPES_KEY)
                    .values().stream()
                    .map(PluginTypeDefinition::tags)
                    .flatMap(tags -> tags.stream())
                    .map(ResourceLocation::toString)
                    .collect(Collectors.toSet());
            allPluginTypeTagsComputed = true;
            pluginTypeTagsFlattenCount++;
        }
        return allPluginTypeTags;
    }

    /**
     * Returns how many times the flattened {@code plugin_types} tag union was
     * actually (re)computed this reload. Exposed for tests so the
     * "flatten once" guarantee is observable directly rather than through the
     * indirect {@code entrySet} count.
     *
     * @return the number of times {@link #allPluginTypeTags()} recomputed the
     *         flattened union (0 when never queried, 1 after the first query)
     */
    int pluginTypeTagsFlattenCount() {
        return pluginTypeTagsFlattenCount;
    }
}
