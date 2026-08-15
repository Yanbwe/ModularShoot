package org.yanbwe.modularshoot.state;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-player state sync throttle manager (设计文档 §同步节流策略 —
 * per-player 域, mirroring {@link GunSyncThrottleManager}).
 *
 * <p>Per-player state may change at high frequency (e.g. heat accumulation or
 * ammo counters written every tick). The framework's {@code PLAYER_STATE}
 * attachment is <em>not</em> configured with NeoForge's automatic
 * {@code .sync(...)} (task 3.3 removed it); instead genuine changes are flushed
 * to the owning client at most once every
 * {@link #THROTTLE_INTERVAL_TICKS} ticks via
 * {@link org.yanbwe.modularshoot.network.PlayerStateSyncService}, driven by
 * this manager and {@link PlayerStateSyncTickHandler}.</p>
 *
 * <h2>Throttle algorithm</h2>
 * <ol>
 *   <li>{@link PlayerState} write accessors install the new payload with
 *       {@link net.minecraft.world.entity.player.Player#setData} (kept immediate
 *       so reads and persistence stay correct) and then call {@link #markDirty}
 *       &mdash; no client sync fires at that point because the attachment has no
 *       auto-sync handler.</li>
 *   <li>{@link PlayerStateSyncTickHandler} polls every server tick: if
 *       {@link #shouldSync} returns {@code true} (dirty <strong>and</strong> at
 *       least {@code THROTTLE_INTERVAL_TICKS} ticks since the last sync), the
 *       handler sends a {@link org.yanbwe.modularshoot.network.PlayerStateS2CPacket}
 *       and calls {@link #markSynced} to clear the dirty flag and stamp the
 *       tick.</li>
 *   <li><b>Critical moments</b> (player login) bypass the throttle entirely and
 *       sync immediately via
 *       {@link org.yanbwe.modularshoot.network.PlayerStateSyncService}. These
 *       paths do not consult this manager.</li>
 * </ol>
 *
 * <h2>State lifetime</h2>
 * <p>The throttle map is <b>runtime-only</b> &mdash; it is never persisted to
 * NBT. Entries are periodically evicted by {@link #cleanup} to prevent
 * unbounded growth from disconnected players.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All operations are thread-safe via {@link ConcurrentHashMap}. The
 * singleton is safe to call from any thread, though in practice all calls
 * originate from the server tick thread or the hook write path.</p>
 *
 * @see PlayerState
 * @see PlayerStateSyncTickHandler
 */
public final class PlayerStateThrottleManager {

    /**
     * Minimum ticks between two throttled per-player state syncs.
     *
     * <p>2 ticks = 100&nbsp;ms, matching {@link GunSyncThrottleManager}'s
     * interval so per-player and per-gun feedback latency feel identical.</p>
     */
    public static final int THROTTLE_INTERVAL_TICKS = 2;

    /** Singleton instance, shared across all server levels. */
    private static final PlayerStateThrottleManager INSTANCE = new PlayerStateThrottleManager();

    /**
     * Per-player throttle state, keyed by player uuid.
     *
     * <p>Runtime-only; never serialised to NBT. Access is thread-safe via
     * {@link ConcurrentHashMap}.</p>
     */
    private final Map<UUID, ThrottleState> throttleStates = new ConcurrentHashMap<>();

    private PlayerStateThrottleManager() {
    }

    /**
     * Returns the singleton manager instance.
     *
     * @return the shared {@link PlayerStateThrottleManager}
     */
    public static PlayerStateThrottleManager getInstance() {
        return INSTANCE;
    }

    /**
     * Marks a player's per-player state as dirty. Called by {@link PlayerState}
     * write accessors after a successful genuine write (which itself already
     * performed the {@code setData} so reads/persistence stay correct).
     *
     * <p>Does <strong>not</strong> trigger an immediate sync. The next
     * {@link PlayerStateSyncTickHandler} tick will flush the change subject to
     * the throttle interval. The {@code lastSyncTick} is preserved so that
     * repeated dirty marks within the same throttle window do not reset the
     * interval.</p>
     *
     * @param playerUuid the uuid of the player whose state changed; must not be
     *                   {@code null}
     */
    public void markDirty(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        throttleStates.compute(playerUuid, (key, existing) -> {
            // 已 dirty → 复用现有 record，避免同一窗口内多次写入产生分配。
            if (existing != null && existing.dirty()) {
                return existing;
            }
            return existing == null
                    ? new ThrottleState(true, 0L)
                    : new ThrottleState(true, existing.lastSyncTick());
        });
    }

    /**
     * Checks whether a player's per-player state should be synced this tick.
     *
     * <p>Returns {@code true} only when the player's state is dirty
     * <strong>and</strong> at least {@link #THROTTLE_INTERVAL_TICKS} ticks have
     * elapsed since the last throttled sync. This is the gate consulted by
     * {@link PlayerStateSyncTickHandler} every server tick.</p>
     *
     * @param playerUuid  the uuid of the player to check
     * @param currentTick the current level game time
     * @return {@code true} if a throttled sync should be sent this tick
     */
    public boolean shouldSync(UUID playerUuid, long currentTick) {
        ThrottleState state = throttleStates.get(playerUuid);
        if (state == null || !state.dirty()) {
            return false;
        }
        return currentTick - state.lastSyncTick() >= THROTTLE_INTERVAL_TICKS;
    }

    /**
     * Marks a player as synced: clears the dirty flag and records the sync tick.
     *
     * <p>Called by {@link PlayerStateSyncTickHandler} immediately after sending
     * a {@link org.yanbwe.modularshoot.network.PlayerStateS2CPacket} so that
     * subsequent dirty marks within the next
     * {@link #THROTTLE_INTERVAL_TICKS} ticks are held back.</p>
     *
     * @param playerUuid  the uuid of the player that was synced; must not be
     *                    {@code null}
     * @param currentTick the current level game time
     */
    public void markSynced(UUID playerUuid, long currentTick) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        throttleStates.put(playerUuid, new ThrottleState(false, currentTick));
    }

    /**
     * Removes the throttle entry for a single player (e.g. on logout) without
     * affecting other players' entries.
     *
     * @param playerUuid the uuid of the player to remove; must not be
     *                   {@code null}
     */
    public void remove(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        throttleStates.remove(playerUuid);
    }

    /**
     * Removes throttle entries for players not in the provided active set,
     * preventing unbounded growth from disconnected players.
     *
     * @param activePlayerUuids the set of player uuids to retain; must not be
     *                          {@code null}
     */
    public void cleanup(Set<UUID> activePlayerUuids) {
        Objects.requireNonNull(activePlayerUuids, "activePlayerUuids");
        throttleStates.keySet().retainAll(activePlayerUuids);
    }

    /**
     * Immutable per-player throttle state.
     *
     * @param dirty        whether the player's state has been modified since the
     *                     last throttled sync
     * @param lastSyncTick the game time at which the last throttled sync was
     *                     sent; {@code 0} when the player has never been synced
     *                     through the throttle path
     */
    private record ThrottleState(boolean dirty, long lastSyncTick) {
    }
}
