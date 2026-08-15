package org.yanbwe.modularshoot.registry;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-{@link Registry} weak-reference cache for definition-class lookups.
 *
 * <p>The hot path of every facade (gun/plugin/plugin-type/state/variant/
 * shooter) repeatedly executes
 * {@code registryAccess.registry(key).flatMap(r -> r.getOptional(id))} for ids
 * that have already been resolved, and {@code /reload} swaps the whole
 * {@link Registry} instance. This cache eliminates the repeated
 * {@link Registry#getOptional} calls without ever serving stale data across a
 * reload: results are keyed by the {@link Registry} <em>instance</em> through
 * a {@link WeakHashMap}, so
 * <ul>
 *   <li>the same {@link Registry} instance resolved from a {@link RegistryAccess}
 *       returns the cached result on every subsequent query;</li>
 *   <li>a new {@link Registry} instance (after {@code /reload}) is a cache miss
 *       and is freshly resolved — the old entry is never consulted because a
 *       different instance is used as the lookup key;</li>
 *   <li>once the caller drops every reference to an old {@link Registry},
 *       both the weak key and its entry (which holds no strong reference back
 *       to the registry) become collectable.</li>
 * </ul>
 *
 * <p>Misses are cached too, so a repeated query for a non-existent id does not
 * re-enter the underlying registry; the returned {@code Optional.empty()}
 * matches the uncached fallback semantics exactly.</p>
 *
 * <p>Instances are safe for concurrent use: the outer map is synchronized and
 * the per-registry id maps are {@link ConcurrentHashMap}s. Each facade owns
 * one {@code static final} instance shared by every caller.</p>
 *
 * @param <T> the definition type stored in the cached registry
 */
public final class RegistryLookupCache<T> {

    /**
     * Weak-keyed outer map ({@code Registry<T> -> per-id resolved results}).
     *
     * <p>Synchronized because {@link WeakHashMap} itself is not thread-safe.
     * The value map never references the {@link Registry}, so entries do not
     * keep their weak key alive.</p>
     */
    private final Map<Registry<T>, Map<ResourceLocation, Optional<T>>> resolved =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Weak-keyed outer map for bulk id sets ({@code Registry<T> -> copied id
     * set}). Kept separate so a caller of only {@link #get} never pays the
     * cost of materialising and copying the whole key set.</p>
     */
    private final Map<Registry<T>, Set<ResourceLocation>> allIds =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Weak-keyed outer map for membership checks ({@code Registry<T> ->
     * id -> containsKey result}); cached independently of {@link #get} because
     * a registry may define {@code containsKey} with different semantics and
     * the two do not share resolution entries.</p>
     */
    private final Map<Registry<T>, Map<ResourceLocation, Boolean>> containsKey =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Tests whether an id is registered, caching the result per
     * {@link Registry} instance and preserving the registry's own
     * {@link Registry#containsKey} semantics.
     *
     * @param access the runtime registry view
     * @param key    the registry key to look the registry up by
     * @param id     the definition id to test
     * @return {@code true} when the id is registered; {@code false} when the
     *         registry is absent or the id is not registered
     */
    public boolean containsKey(RegistryAccess access, ResourceKey<Registry<T>> key, ResourceLocation id) {
        Registry<T> registry = access.registry(key).orElse(null);
        if (registry == null) {
            return false;
        }
        Map<ResourceLocation, Boolean> byId = containsKeyMap(registry);
        return byId.computeIfAbsent(id, registry::containsKey);
    }

    /**
     * Resolves a single definition id against the supplied registry view,
     * caching the result per {@link Registry} instance.
     *
     * @param access the runtime registry view (from a loaded world)
     * @param key    the registry key to look the registry up by
     * @param id     the definition id to resolve
     * @return the cached resolution, or {@link Optional#empty()} when the
     *         registry is absent or the id is not registered
     */
    public Optional<T> get(RegistryAccess access, ResourceKey<Registry<T>> key, ResourceLocation id) {
        Registry<T> registry = access.registry(key).orElse(null);
        if (registry == null) {
            return Optional.empty();
        }
        Map<ResourceLocation, Optional<T>> byId = idMap(registry);
        return byId.computeIfAbsent(id, registry::getOptional);
    }

    /**
     * Resolves every registered id in the supplied registry view, caching the
     * copied id set per {@link Registry} instance.
     *
     * @param access the runtime registry view
     * @param key    the registry key to look the registry up by
     * @return an immutable copied set of all registered ids, or an empty set
     *         when the registry is absent
     */
    public Set<ResourceLocation> getAllIds(RegistryAccess access, ResourceKey<Registry<T>> key) {
        Registry<T> registry = access.registry(key).orElse(null);
        if (registry == null) {
            return Set.of();
        }
        synchronized (allIds) {
            return allIds.computeIfAbsent(registry, r -> Set.copyOf(r.keySet()));
        }
    }

    /**
     * Returns the per-registry id map, creating it on first use.
     *
     * <p>All access to {@link #resolved} must be synchronized because
     * {@link WeakHashMap} may be touched concurrently from the shooting hot
     * path.</p>
     */
    private Map<ResourceLocation, Optional<T>> idMap(Registry<T> registry) {
        synchronized (resolved) {
            return resolved.computeIfAbsent(registry,
                    r -> new ConcurrentHashMap<>());
        }
    }

    /**
     * Returns the per-registry membership map, creating it on first use.
     *
     * <p>Must be synchronized for the same reason as {@link #idMap}.</p>
     */
    private Map<ResourceLocation, Boolean> containsKeyMap(Registry<T> registry) {
        synchronized (containsKey) {
            return containsKey.computeIfAbsent(registry,
                    r -> new ConcurrentHashMap<>());
        }
    }
}
