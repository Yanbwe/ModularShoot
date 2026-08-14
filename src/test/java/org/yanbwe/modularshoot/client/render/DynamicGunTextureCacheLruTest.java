package org.yanbwe.modularshoot.client.render;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.render.DynamicGunTextureCache.LruStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LruStore}, the bounded LRU container extracted from
 * {@link DynamicGunTextureCache} (审查优化 P19 配套: 逐出策略可单测).
 *
 * <p>The container is the part of the texture cache that decides which
 * composite to evict when the cache exceeds {@code MAX_ENTRIES}; the GPU
 * release side-effect is injected as the {@code onEvict} consumer so tests
 * can pin the <em>policy</em> without a client. The production behaviour
 * under test: access-order recency (a hit or a replace refreshes the
 * entry), oldest-entry eviction over capacity, and no eviction when a
 * replacement does not grow the map.</p>
 */
class DynamicGunTextureCacheLruTest {

    private static final int MAX = 2;

    /** A store recording evicted values instead of releasing GPU textures. */
    private static LruStore<String, String> store(List<String> evicted) {
        return new LruStore<>(MAX, evicted::add);
    }

    @Test
    void evictsLeastRecentlyUsedWhenInsertOverCapacity() {
        List<String> evicted = new ArrayList<>();
        LruStore<String, String> store = store(evicted);
        store.put("a", "A");
        store.put("b", "B");
        store.get("a");                      // refresh a → b becomes LRU
        store.put("c", "C");

        assertEquals(List.of("B"), evicted, "the least-recently-used entry (b) is evicted");
        assertEquals("A", store.get("a"));
        assertNull(store.get("b"), "evicted entry is gone");
        assertEquals("C", store.get("c"));
    }

    @Test
    void evictsOldestWhenNeverAccessed() {
        List<String> evicted = new ArrayList<>();
        LruStore<String, String> store = store(evicted);
        store.put("a", "A");
        store.put("b", "B");
        store.put("c", "C");                 // at capacity: evicts a (never accessed)

        assertEquals(List.of("A"), evicted);
        assertNull(store.get("a"));
        assertEquals("B", store.get("b"));
        assertEquals("C", store.get("c"));
    }

    @Test
    void replacingExistingKeyRefreshesRecencyWithoutEvicting() {
        List<String> evicted = new ArrayList<>();
        LruStore<String, String> store = store(evicted);
        store.put("a", "A1");
        store.put("b", "B");
        store.put("a", "A2");                // replace: size unchanged, no eviction

        assertTrue(evicted.isEmpty(), "a same-size replacement must not evict anything");
        assertEquals("A2", store.get("a"), "replacement value is stored");
        assertEquals("B", store.get("b"));
    }

    @Test
    void replacingRefreshedKeyMakesItMostRecent() {
        LruStore<String, String> store = store(new ArrayList<>());
        store.put("a", "A1");
        store.put("b", "B");
        store.put("a", "A2");                // a is now most recent
        store.put("c", "C");                 // evicts b, not a

        assertNull(store.get("b"), "the never-touched-after-insert entry is evicted");
        assertEquals("A2", store.get("a"));
        assertEquals("C", store.get("c"));
    }

    @Test
    void clearDropsEverything() {
        List<String> evicted = new ArrayList<>();
        LruStore<String, String> store = store(evicted);
        store.put("a", "A");
        store.put("b", "B");
        assertFalse(store.isEmpty());

        store.clear();

        assertTrue(store.isEmpty());
        assertEquals(0, store.size());
        assertNull(store.get("a"));
        assertNull(store.get("b"));
        assertTrue(evicted.isEmpty(), "clear is a bulk drop; release is done by the caller (DynamicGunTextureCache.clear)");
    }
}
