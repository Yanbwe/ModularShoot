package org.yanbwe.modularshoot.client.tooltip;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/**
 * Bounded short-term cache for tooltip build results.
 *
 * <p>Tooltips are rebuilt every frame the mouse hovers an item; most frames the
 * inputs (stack, registry version, modifier keys, gun-data version) are
 * unchanged, so the result can be reused. This cache stores
 * {@code TooltipCacheKey -> List<Component>} with a small capacity and
 * access-order LRU eviction.</p>
 *
 * <p><b>Immutable values (审查 Low fix):</b> results are stored (and returned)
 * as unmodifiable lists via {@link List#copyOf}. A cached {@code List<Component>}
 * is shared across frames and callers, so it must not be mutated; callers that
 * want to extend the returned lines (e.g. appending to a tooltip) must copy it
 * first.</p>
 *
 * <p><b>Threading:</b> the cache is only mutated/read from the render thread,
 * which is single-threaded, so a plain {@link LinkedHashMap} suffices. Registry
 * reloads occur on the main thread, but because {@link TooltipCacheKey}
 * incorporates the registry version, a reload produces a cache miss instead of
 * a race — the render thread never reads a stale entry for the new registry.
 * An optional {@link #clear()} is provided for explicit invalidation should an
 * event-driven flush ever be needed.</p>
 */
public final class TooltipCache {

    /** Hard upper bound to prevent unbounded growth across long sessions. */
    private static final int MAX_ENTRIES = 256;

    /** Access-order map so repeated hits promote entries and evict the coldest. */
    private final Map<TooltipCacheKey, List<Component>> entries =
            new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Returns the cached result for {@code key}, computing it via
     * {@code compute} on a miss and storing it.
     *
     * @param key     the cache key (must capture every input that changes output)
     * @param compute the build supplier, invoked only on a cache miss
     * @return the cached or freshly computed result as an <em>immutable</em>
     *         list; callers must not attempt to mutate it
     */
    public List<Component> getOrCompute(TooltipCacheKey key, Supplier<List<Component>> compute) {
        List<Component> cached = entries.get(key);
        if (cached != null) {
            return cached;
        }
        List<Component> built = compute.get();
        // Snapshot into an immutable list so a shared cached value can never be
        // mutated by a later caller (审查 Low fix). Empty lists are cacheable.
        List<Component> immutable = built == null ? List.of() : List.copyOf(built);
        entries.put(key, immutable);
        evictIfNeeded();
        return immutable;
    }

    /**
     * Returns whether an entry is currently cached for {@code key}.
     *
     * @param key the cache key
     * @return {@code true} if a result is stored under the key
     */
    public boolean containsKey(TooltipCacheKey key) {
        return entries.containsKey(key);
    }

    /**
     * Returns the number of currently cached entries.
     *
     * @return the cache size
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes every cached entry.
     */
    public void clear() {
        entries.clear();
    }

    private void evictIfNeeded() {
        while (entries.size() > MAX_ENTRIES) {
            TooltipCacheKey eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }
}
