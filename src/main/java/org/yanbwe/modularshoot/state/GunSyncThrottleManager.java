package org.yanbwe.modularshoot.state;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-gun sync throttle manager (设计文档 §同步节流策略, lines
 * 1843-1851).
 *
 * <p>Per-gun state may change at high frequency (e.g. heat accumulation every
 * tick). Syncing on every change would flood the network, so the framework
 * throttles state-only syncs to at most one {@link
 * org.yanbwe.modularshoot.network.GunSyncS2CPacket} per gun every
 * {@link #THROTTLE_INTERVAL_TICKS} ticks (100&nbsp;ms).</p>
 *
 * <h2>Throttle algorithm</h2>
 * <ol>
 *   <li>A hook modifies state via {@link GunState} &rarr; {@link #markDirty}
 *       sets {@code dirty = true} <em>without</em> sending a packet.</li>
 *   <li>{@link GunSyncTickHandler} polls every server tick: if
 *       {@link #shouldSync} returns {@code true} (dirty <strong>and</strong>
 *       at least {@code THROTTLE_INTERVAL_TICKS} ticks since the last sync),
 *       the handler sends a {@link GunSyncS2CPacket} and calls
 *       {@link #markSynced} to clear the dirty flag and stamp the tick.</li>
 *   <li><b>Critical moments</b> (main-hand switch, plugin install/uninstall,
 *       player login) bypass the throttle entirely and sync immediately via
 *       {@link org.yanbwe.modularshoot.network.GunSyncService#syncToPlayer}.
 *       These paths do not consult this manager.</li>
 * </ol>
 *
 * <h2>State lifetime</h2>
 * <p>The throttle map is <b>runtime-only</b> &mdash; it is never persisted to
 * NBT. Entries are periodically evicted by {@link #cleanup} to prevent
 * unbounded growth from guns that have been dropped, stored, or destroyed.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All operations are thread-safe via {@link ConcurrentHashMap}. The
 * singleton is safe to call from any thread, though in practice all calls
 * originate from the server tick thread or the hook write path.</p>
 *
 * @see GunSyncTickHandler
 * @see GunState
 */
public final class GunSyncThrottleManager {

    /**
     * Minimum ticks between two throttled state syncs for a single gun.
     *
     * <p>2 ticks = 100&nbsp;ms. The value balances bandwidth (shorter
     * intervals flood the network for high-frequency state) against feedback
     * latency (longer intervals make state changes feel sluggish). At 2 ticks
     * players perceive state updates as instantaneous (设计文档 line 1849).</p>
     */
    public static final int THROTTLE_INTERVAL_TICKS = 2;

    /** Singleton instance, shared across all server levels. */
    private static final GunSyncThrottleManager INSTANCE = new GunSyncThrottleManager();

    /**
     * Per-gun throttle state, keyed by {@code gunInstanceUuid}.
     *
     * <p>Runtime-only; never serialised to NBT. Access is thread-safe via
     * {@link ConcurrentHashMap}.</p>
     */
    private final Map<UUID, ThrottleState> throttleStates = new ConcurrentHashMap<>();

    private GunSyncThrottleManager() {
    }

    /**
     * Returns the singleton manager instance.
     *
     * @return the shared {@link GunSyncThrottleManager}
     */
    public static GunSyncThrottleManager getInstance() {
        return INSTANCE;
    }

    /**
     * Marks a gun's state as dirty. Called by {@link GunState} set accessors
     * after a successful state write.
     *
     * <p>Does <strong>not</strong> trigger an immediate sync. The next
     * {@link GunSyncTickHandler} tick will flush the change subject to the
     * throttle interval. The {@code lastSyncTick} is preserved so that
     * repeated dirty marks within the same throttle window do not reset the
     * interval.</p>
     *
     * @param gunInstanceUuid the uuid of the gun whose state changed; must not
     *                        be {@code null}
     */
    public void markDirty(UUID gunInstanceUuid) {
        Objects.requireNonNull(gunInstanceUuid, "gunInstanceUuid");
        throttleStates.compute(gunInstanceUuid, (key, existing) ->
                existing == null
                        ? new ThrottleState(true, 0L)
                        : new ThrottleState(true, existing.lastSyncTick()));
    }

    /**
     * Checks whether a gun should be synced this tick.
     *
     * <p>Returns {@code true} only when the gun's state is dirty
     * <strong>and</strong> at least {@link #THROTTLE_INTERVAL_TICKS} ticks
     * have elapsed since the last throttled sync. This is the gate consulted
     * by {@link GunSyncTickHandler} every server tick.</p>
     *
     * @param gunInstanceUuid the uuid of the gun to check
     * @param currentTick     the current level game time
     *                        ({@link net.minecraft.world.level.Level#getGameTime()})
     * @return {@code true} if a throttled sync should be sent this tick
     */
    public boolean shouldSync(UUID gunInstanceUuid, long currentTick) {
        ThrottleState state = throttleStates.get(gunInstanceUuid);
        if (state == null || !state.dirty()) {
            return false;
        }
        return currentTick - state.lastSyncTick() >= THROTTLE_INTERVAL_TICKS;
    }

    /**
     * Marks a gun as synced: clears the dirty flag and records the sync tick.
     *
     * <p>Called by {@link GunSyncTickHandler} immediately after sending a
     * {@link org.yanbwe.modularshoot.network.GunSyncS2CPacket} so that
     * subsequent dirty marks within the next
     * {@link #THROTTLE_INTERVAL_TICKS} ticks are held back.</p>
     *
     * @param gunInstanceUuid the uuid of the gun that was synced; must not be
     *                        {@code null}
     * @param currentTick     the current level game time
     */
    public void markSynced(UUID gunInstanceUuid, long currentTick) {
        Objects.requireNonNull(gunInstanceUuid, "gunInstanceUuid");
        throttleStates.put(gunInstanceUuid, new ThrottleState(false, currentTick));
    }

    /**
     * Removes throttle entries for guns not in the provided active set,
     * preventing unbounded growth from guns that have been dropped, stored,
     * or destroyed.
     *
     * <p>The caller supplies the set of gun uuids that are still relevant
     * (e.g. all main-hand guns of online players, collected by
     * {@link GunSyncTickHandler}). Entries whose uuid is absent from the set
     * are evicted. When a gun re-enters a player's main hand it receives a
     * fresh throttle state ({@code lastSyncTick = 0}) and is synced on the
     * next dirty mark &mdash; the main-hand switch itself is already covered
     * by the immediate-sync path in {@link GunSyncService}.</p>
     *
     * @param activeGunUuids the set of gun uuids to retain; must not be
     *                       {@code null}
     */
    public void cleanup(Set<UUID> activeGunUuids) {
        Objects.requireNonNull(activeGunUuids, "activeGunUuids");
        throttleStates.keySet().retainAll(activeGunUuids);
    }

    /**
     * Immutable per-gun throttle state.
     *
     * @param dirty        whether the gun's state has been modified since the
     *                     last throttled sync
     * @param lastSyncTick the game time at which the last throttled sync was
     *                     sent; {@code 0} when the gun has never been synced
     *                     through the throttle path
     */
    private record ThrottleState(boolean dirty, long lastSyncTick) {
    }
}
