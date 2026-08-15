package org.yanbwe.modularshoot.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Composite-result cache keyed first by an arbitrary <em>registry token</em>
 * (weak key) and then by an immutable service-level key.
 *
 * <p>This mirrors {@link RegistryLookupCache}'s reload contract one level
 * higher: services that derive a whole result (trait merge, extra-value
 * aggregates) from one or more dynamic registries cache that result per
 * {@link net.minecraft.core.Registry} <em>instance</em>. When {@code /reload}
 * swaps in a new registry instance the token differs, the old cache entry is
 * never consulted, and the result is rebuilt — so reloads never serve stale
 * data. Once the caller drops every reference to an old registry the weak key
 * (and its entry, which holds no strong reference back) becomes
 * collectable.</p>
 *
 * <p>The token is an opaque key object chosen by the caller (typically the
 * {@code Registry} instance itself, or a small record combining several
 * registry instances). Tokens must implement structural {@code equals}/
 * {@code hashCode} consistent with the identity of the registries they wrap —
 * {@code Registry} implementations use object identity, so two wrappers around
 * the same registries are equal while reloaded registries are not.</p>
 *
 * <p>Each per-token inner map is <b>bounded</b> to {@link #INNER_CAPACITY}
 * entries with a simple least-recently-used eviction (an access-ordered
 * {@link LinkedHashMap} whose {@code removeEldestEntry} drops the least
 * recently used entry once the capacity is exceeded). This caps the memory
 * footprint per registry even when callers key by monotonic values (e.g.
 * increasing {@code modifierVersion}s, contributor versions or ever-changing
 * plugin lists) — such entries would otherwise accumulate without bound for
 * the lifetime of a single registry instance. Eviction only ever causes a
 * recompute on a later access, never a stale result.</p>
 *
 * <p>Thread-safe: the outer map is synchronized ({@link WeakHashMap} is not)
 * and each per-token inner map is a {@link
 * Collections#synchronizedMap(Map) synchronized} access-ordered
 * {@link LinkedHashMap}. {@link Map#computeIfAbsent} is atomic per key, so a
 * loader for a given key runs at most once; concurrent callers racing on the
 * same miss all observe the single computed result.</p>
 *
 * @param <K> the immutable composite key identifying one logical result
 * @param <V> the cached result type
 */
public final class RegistryKeyedCache<K, V> {

    /**
     * Maximum number of entries retained per registry token. Evicted entries
     * are simply recomputed on their next access.
     */
    private static final int INNER_CAPACITY = 1024;

    /** Weak-keyed outer map: token -> per-token cached results. */
    private final Map<Object, Map<K, V>> entries =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Returns the cached result for the given token + key, computing and
     * storing it (thread-safely, per token) when absent.
     *
     * @param token  the registry token (weak key); {@code null} is allowed as a
     *               key but a caller signalling "no cache" should skip this
     *               method instead of passing {@code null}
     * @param key    the immutable composite key; must have the {@code equals}/
     *               {@code hashCode} the caller wants
     * @param loader the pure function producing the result on a miss
     * @return the cached (possibly just-computed) result
     */
    public V computeIfAbsent(Object token, K key, Supplier<? extends V> loader) {
        return tokenMap(token).computeIfAbsent(key, k -> loader.get());
    }

    /**
     * Returns the per-token map, creating it on first use under the
     * synchronized outer-map lock ({@link WeakHashMap} is not thread-safe).
     */
    private Map<K, V> tokenMap(Object token) {
        synchronized (entries) {
            return entries.computeIfAbsent(token, t -> newInnerMap());
        }
    }

    /**
     * Creates a new bounded inner map: a synchronized access-ordered
     * {@link LinkedHashMap} that evicts the least recently used entry once it
     * exceeds {@link #INNER_CAPACITY} entries.
     */
    private static <K, V> Map<K, V> newInnerMap() {
        LinkedHashMap<K, V> map = new LinkedHashMap<>(Math.min(INNER_CAPACITY, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > INNER_CAPACITY;
            }
        };
        return Collections.synchronizedMap(map);
    }
}
