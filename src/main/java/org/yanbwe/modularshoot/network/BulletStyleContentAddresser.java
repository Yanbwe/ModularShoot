package org.yanbwe.modularshoot.network;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side content addresser for the "flight-invariant" bullet style
 * payload (阶段 2 / 任务 2.3).
 *
 * <p>Assigns a stable, positive wire id to each distinct
 * {@link BulletStyleFingerprint}, caching the full {@link BulletStyleData}
 * payload under that id. The caller (server sync service) passes whether the
 * receiving player already knows the id; the addresser then returns either
 * the id alone (player knows it) or the id <em>plus</em> the full payload
 * (first transmission to that player). This guarantees the full style is sent
 * over the wire at most once per client per fingerprint, while delta and
 * full-sync packets only carry the wire id afterwards.</p>
 *
 * <p><b>Bounded eviction (审查修复).</b> The cache is a bounded LRU: a
 * least-recently-used style is evicted once the capacity is exceeded, so the
 * server-side maps cannot grow without bound over a long-lived server even
 * with fingerprints that vary per bullet (including per-shooter content). When
 * an id is evicted, a later re-encounter of the same content is assigned a
 * fresh, never-reused id and re-transmitted in full to any client that no
 * longer has it — the periodic force-full-sync (which always attaches full
 * payloads, see {@code BulletSyncService}) self-heals clients that missed the
 * original full transmission, so no explicit per-client known-set cleanup is
 * required here.</p>
 *
 * <p>Thread-safety: instances are not synchronized; the server sync layer
 * drives it from a single server thread. Wire ids should ideally be kept
 * stable across the same server lifetime, so the sync layer should hold one
 * long-lived instance.</p>
 */
public final class BulletStyleContentAddresser {

    /** Default maximum number of distinct styles kept before LRU eviction. */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;

    /** fingerprint → stable wire id, access-ordered for LRU eviction. */
    private final Map<String, Integer> fingerprintToId;

    /** wire id → full style payload, access-ordered for LRU eviction. */
    private final Map<Integer, BulletStyleData> idToStyle;

    private int nextId = 1;

    public BulletStyleContentAddresser() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity maximum number of distinct styles retained before LRU
     *                 eviction; must be positive
     */
    public BulletStyleContentAddresser(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.fingerprintToId = new LinkedHashMap<>(16, 0.75f, true);
        this.idToStyle = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * Returns the wire id already assigned to a fingerprint, or {@code null}
     * if this fingerprint has not been seen yet. Read-only — does not allocate
     * a new id and does <em>not</em> affect LRU recency. Used by the server to
     * determine whether the receiving client already knows a style id before
     * calling {@link #resolve}.
     *
     * @param fingerprint the content fingerprint
     * @return the existing wire id, or {@code null}
     */
    @Nullable
    public Integer peekId(String fingerprint) {
        return fingerprintToId.get(fingerprint);
    }

    /**
     * Resolves a style payload to a wire id, returning the full payload only
     * when the caller reports the client does not yet know the id.
     *
     * <p>On the first encounter of a fingerprint a fresh wire id is assigned
     * and the full style is cached (subject to LRU eviction). Accessing an
     * existing entry refreshes its LRU recency.</p>
     *
     * @param style       the flight-invariant style payload
     * @param fingerprint the content fingerprint (see {@link BulletStyleFingerprint})
     * @param clientKnows whether the receiving player already knows this wire id
     * @return a reference carrying the (stable) wire id, and the full payload
     *         on first transmission to this client
     */
    public BulletStyleRef resolve(BulletStyleData style, String fingerprint, boolean clientKnows) {
        Integer existing = fingerprintToId.get(fingerprint);
        if (existing != null) {
            // Touch recency for both maps so the id is not evicted while hot.
            idToStyle.get(existing);
            if (clientKnows) {
                return new BulletStyleRef(existing, null);
            }
            return new BulletStyleRef(existing, idToStyle.get(existing));
        }
        int id = nextId++;
        fingerprintToId.put(fingerprint, id);
        idToStyle.put(id, style);
        evictIfNeeded();
        // A brand-new fingerprint is always unknown to every client -> full style.
        return new BulletStyleRef(id, style);
    }

    /**
     * Returns the full style payload for a wire id, or {@code null} if the id
     * is not known to this addresser (should not happen on the server, which
     * assigns every id itself).
     *
     * @param styleId the wire id to look up
     * @return the cached full style payload, or {@code null}
     */
    @Nullable
    public BulletStyleData get(int styleId) {
        return idToStyle.get(styleId);
    }

    /**
     * Evicts the least-recently-used entry from both maps once the cache
     * exceeds its capacity, keeping the id and fingerprint maps in lock-step.
     * Evicted ids are never reused (ids are monotonically increasing), so a
     * re-encountered fingerprint gets a fresh id and is re-sent in full.
     */
    private void evictIfNeeded() {
        if (fingerprintToId.size() <= capacity) {
            return;
        }
        String oldestFingerprint = fingerprintToId.keySet().iterator().next();
        Integer evictedId = fingerprintToId.remove(oldestFingerprint);
        if (evictedId != null) {
            idToStyle.remove(evictedId);
        }
    }

    /**
     * A resolved content reference: a stable wire id plus the full style
     * payload only when it must be transmitted now.
     *
     * @param styleId the stable wire id
     * @param style   the full payload when this transmission must carry it,
     *                or {@code null} when the client already knows the id
     */
    public record BulletStyleRef(int styleId, @Nullable BulletStyleData style) {
        /** @return {@code true} when this transmission must carry the full payload. */
        public boolean hasFullStyle() {
            return style != null;
        }
    }
}
