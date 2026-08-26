package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.config.ModularShootCommonConfig;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.ComposedBulletStyle;
import org.yanbwe.modularshoot.network.ClientBulletSnapshot;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side per-tick bullet broadcast service (设计文档 §同步策略).
 *
 * <p>Listens to {@link LevelTickEvent.Post} — which fires <em>after</em>
 * {@code BulletTickHandler}'s {@code Pre} simulation — so bullet positions
 * are already advanced and collisions resolved before sync. For every server
 * tick the service collects all active bullets from the dimension's
 * {@link BulletManager}, culls them per-player against that player's
 * render distance, builds a per-player {@link BulletS2CPacket} and dispatches it via
 * {@link PacketDistributor#sendToPlayer}.</p>
 *
 * <p><b>Server-only.</b> A {@code level.isClientSide()} guard ensures the
 * service only runs on the authoritative server, matching the
 * NeoForge-recommended pattern for {@code LevelTickEvent} handlers.</p>
 *
 * <h2>Incremental sync (D-02, 设计文档 §同步策略 lines 2042-2043)</h2>
 * <p>The first packet for a bullet carries full information (id, position,
 * direction, visual style, size, shooter); subsequent update packets carry
 * only the id and the current position/direction. The service maintains
 * per-client state ({@link #CLIENT_STATES}) recording the last-synced
 * position/direction for each bullet id. Each tick it diffs the current
 * bullet set against this state and builds a three-bucket
 * {@link BulletS2CPacket#delta(List, List, List) delta packet}:</p>
 * <ul>
 *   <li>new bullets (not in client state) → {@link BulletS2CPacket.FullBulletEntry}</li>
 *   <li>changed bullets (position/direction moved past a fixed-point
 *       1/128-block quantization cell, and the distance band allows an
 *       update this tick) →
 *       {@link BulletS2CPacket.DeltaBulletEntry}; sub-quantum / sub-pixel
 *       drift no longer produces a packet every tick (阶段 2 / 任务 2.2)</li>
 *   <li>removed bullets (in client state but no longer active) →
 *       {@link BulletS2CPacket#removedBulletIds()}</li>
 * </ul>
 *
 * <h2>Force-full-sync (drift recovery)</h2>
 * <p>Every {@link #DEFAULT_FULL_SYNC_INTERVAL_TICKS} ticks (configurable via
 * {@code modularshoot-common.toml}, 审查 O7) the service sends a
 * {@link BulletS2CPacket#fullSync(List) force-full-sync packet} instead of a
 * delta packet. The client clears its render-object map and rebuilds from
 * the full entries, recovering from any dropped delta packets. This also
 * serves as the initial sync when a player first joins (no client state
 * exists yet).</p>
 *
 * <h2>Short-life bullet guarantee (D-03, 设计文档 §短寿命子弹保证 line 1276)</h2>
 * <p>High-speed / short-range bullets (e.g. shotgun pellets at close range)
 * may be created and removed within the same Pre simulation step, before the
 * Post tick event ever fires. To guarantee such bullets still appear on the
 * client for at least one render frame, {@code ShootingEngine} calls
 * {@link #markBulletCreated(Level, BulletRecord)} immediately after
 * registering a bullet. The service records these in
 * {@link #CREATED_THIS_TICK} and, at tick end, includes them in the
 * {@code newBullets} bucket of the delta packet — <em>even if the bullet was
 * already removed by collision</em>. The client creates the render object;
 * the next tick's delta packet lists the id in {@code removedBulletIds} and
 * the client destroys it. The bullet thus appears for at least one frame.</p>
 *
 * <h2>Render-distance culling (D-04, 设计文档 §同步范围 line 1273)</h2>
 * <p>The sync radius is derived from the player's chunk tracking view
 * ({@link ServerPlayer#getChunkTrackingView()}), which reflects the client's
 * configured render distance (clamped by the server view distance). Each
 * player receives only bullets whose current position falls within that
 * radius of the player's position. When the tracking view is not a
 * {@link ChunkTrackingView.Positioned} (e.g. during dimension transition),
 * the server's view distance is used as a fallback.</p>
 *
 * <p>The per-player candidate set is produced once per tick by the spatial
 * index ({@link BulletManager#getActiveBulletsInRange}) and is the
 * <em>visible subset</em> of bullets passed down as {@code visibleBullets}
 * to the diff builders. Because the broadcast diffs against this subset,
 * a bullet that falls outside it is indistinguishable from a removed bullet:
 * it is dropped from the player's sync state and listed in
 * {@code removedBulletIds}. This is intentional — bullets that leave a
 * player's render radius should disappear on the client just like removed
 * ones — and matches the authoritative "outside visible subset = removed"
 * semantics.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletSyncService {

    /**
     * Sentinel entity id used when the bullet has no shooter (ownerless
     * independent firing).
     */
    private static final int NO_SHOOTER = -1;

    /**
     * Default interval (in ticks) between forced full-sync packets, used to
     * recover from dropped delta packets and prevent client state drift. The
     * live value is read from
     * {@link ModularShootCommonConfig#getFullSyncIntervalTicks()} (审查 O7).
     * Default 100 ticks = 5 seconds.
     */
    static final long DEFAULT_FULL_SYNC_INTERVAL_TICKS = 100L;

    /**
     * Change detection and per-tick update frequency are delegated to the pure
     * {@link BulletDeltaQuantizer} rules (阶段 2 / 任务 2.2): positions are
     * diffed on a fixed-point 1/128-block grid, so sub-quantum / sub-pixel
     * drift no longer spams a delta entry every tick; and bullets beyond the
     * close distance band are decimated to a lower maximum update frequency.
     */
    /**
     * Per-client sync state: for each player, a map of bullet id → last
     * synced position/direction. Used to compute the new/updated/removed
     * diff each tick. Cleared on player logout via
     * {@link #onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent)}.
     */
    private static final Map<ServerPlayer, Map<Integer, BulletState>> CLIENT_STATES =
            new ConcurrentHashMap<>();

    /**
     * Per-player tick counter for the last forced full-sync. When
     * {@code currentTick - lastForceTick >= fullSyncIntervalTicks} (config,
     * 审查 O7) a full-sync packet is sent instead of a delta packet.
     */
    private static final Map<ServerPlayer, Long> LAST_FORCE_FULL_SYNC_TICK =
            new ConcurrentHashMap<>();

    /**
     * Per-dimension list of bullets created this tick, for the short-life
     * bullet guarantee (D-03). Populated by
     * {@link #markBulletCreated(Level, BulletRecord)} and drained at the end
     * of each Post tick. Weak keys allow unloaded dimensions to be
     * garbage-collected.
     */
    private static final Map<Level, List<BulletRecord>> CREATED_THIS_TICK =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Server-wide content addresser for the flight-invariant bullet style
     * payload (阶段 2 / 任务 2.3): assigns a stable wire id per style
     * fingerprint and caches the full {@link BulletStyleData} under that id so
     * each distinct style is transmitted in full at most once per client.
     */
    private static final BulletStyleContentAddresser STYLE_ADDRESSER =
            new BulletStyleContentAddresser();

    /**
     * Per-client set of style wire ids the server has already sent in full to
     * that client. When an id is present, the server sends only the id in
     * subsequent full/delta entries; when absent, the full style is attached.
     * Cleared on logout / dimension change in lock-step with
     * {@link #CLIENT_STATES} and {@link #LAST_FORCE_FULL_SYNC_TICK}.
     */
    private static final Map<ServerPlayer, Set<Integer>> PLAYER_KNOWN_STYLE_IDS =
            new ConcurrentHashMap<>();

    private BulletSyncService() {
    }

    // --- Tick entry point -----------------------------------------------

    /**
     * Fired once per tick per level after the level has finished its work.
     * Guarded to process only the authoritative server side.
     *
     * @param event the post-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        syncBulletsToPlayers(level);
    }

    /**
     * Clears per-client state when a player logs out, preventing unbounded
     * growth of {@link #CLIENT_STATES} and {@link #LAST_FORCE_FULL_SYNC_TICK}.
     *
     * @param event the player-logged-out event
     */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CLIENT_STATES.remove(player);
            LAST_FORCE_FULL_SYNC_TICK.remove(player);
            PLAYER_KNOWN_STYLE_IDS.remove(player);
        }
    }

    /**
     * Clears a player's sync state on dimension change (审查修复: 维度切换
     * 状态残留).
     *
     * <p>Bullet ids are per-dimension counters: after a dimension switch the
     * new dimension's ids can collide with the residue of the old dimension's
     * state, and the client would treat fresh bullets as already-known
     * (delta-only) while it has no render object for them — missing them
     * until the next 5-second force-full-sync. Clearing the state here also
     * resets the force-full-sync tick, so the very first packet after the
     * switch is a full sync that rebuilds the client's render-object map.</p>
     *
     * @param event the dimension-changed event
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CLIENT_STATES.remove(player);
            LAST_FORCE_FULL_SYNC_TICK.remove(player);
            PLAYER_KNOWN_STYLE_IDS.remove(player);
        }
    }

    // --- Short-life bullet guarantee (D-03) -----------------------------

    /**
     * Marks a bullet as created this tick so that the tick-end sync includes
     * it in the {@code newBullets} bucket — even if the bullet is removed by
     * collision before the Post tick event fires (设计文档 §短寿命子弹保证).
     *
     * <p>Called by {@code ShootingEngine} right after a bullet is registered.
     * The actual packet is sent at tick end from
     * {@link #syncBulletsToPlayers(Level)}, aligning with the design doc's
     * "创建 tick 末强制同步一次完整包" requirement.</p>
     *
     * @param level  the level the bullet was created in
     * @param bullet the newly created bullet record
     */
    public static void markBulletCreated(Level level, BulletRecord bullet) {
        CREATED_THIS_TICK.computeIfAbsent(level, k -> Collections.synchronizedList(new ArrayList<>())).add(bullet);
    }

    // --- Core sync logic ------------------------------------------------

    /**
     * Collects the spatial-index-filtered visible bullets per player, drains
     * the created-this-tick list, and dispatches a per-player
     * {@link BulletS2CPacket} to each player in the dimension.
     *
     * @param level the server level whose bullets are being synced
     */
    private static void syncBulletsToPlayers(Level level) {
        BulletManager manager = BulletManager.get(level);
        ServerLevel serverLevel = (ServerLevel) level;
        List<ServerPlayer> players = serverLevel.players();
        // Drain created-this-tick regardless of player count so the list
        // does not leak into the next tick when no players are online.
        List<BulletRecord> createdThisTick = CREATED_THIS_TICK.remove(level);
        if (players.isEmpty()) {
            return;
        }
        // Per-tick style-payload cache: BulletStyleData is player-independent, so
        // one conversion per bullet is shared by every player; the per-player
        // style wire id + whether to attach the full payload is resolved per
        // player from PLAYER_KNOWN_STYLE_IDS (阶段 2 / 任务 2.3).
        Map<Integer, BulletStyleData> styleDataCache = new HashMap<>();
        for (ServerPlayer player : players) {
            // Spatial range query: only inspect bullets within this player's
            // sync radius instead of scanning every bullet in the dimension
            // (eliminates the O(子弹数 × 玩家数) per-tick scan — 阶段 2 /
            // 任务 2.1). The query uses the same Chebyshev horizontal metric as
            // the per-bullet culling checks below, so they remain consistent.
            double syncRadius = getSyncRadius(player);
            Collection<BulletRecord> visibleBullets =
                    manager.getActiveBulletsInRange(player.position(), syncRadius);
            syncPlayerBullets(player, visibleBullets, createdThisTick, serverLevel, styleDataCache);
        }
    }

    /**
     * Builds and sends a per-player {@link BulletS2CPacket} — either a
     * force-full-sync (drift recovery / initial) or an incremental delta.
     *
     * @param player         the player to sync to
     * @param visibleBullets the spatial-index-filtered bullets visible to this
     *                       player (candidate subset); anything outside this
     *                       subset is treated as removed for this player
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param serverLevel    the server level (for gun-registry lookups and tick time)
     * @param styleDataCache the per-tick full-entry conversion cache shared by all players
     */
    private static void syncPlayerBullets(
            ServerPlayer player,
            Collection<BulletRecord> visibleBullets,
            List<BulletRecord> createdThisTick,
            ServerLevel serverLevel,
            Map<Integer, BulletStyleData> styleDataCache) {
        Map<Integer, BulletState> playerStates =
                CLIENT_STATES.computeIfAbsent(player, k -> new HashMap<>());
        long currentTick = serverLevel.getGameTime();
        if (shouldForceFullSync(player, currentTick)) {
            sendForceFullSync(player, visibleBullets, createdThisTick, playerStates, serverLevel, styleDataCache);
            return;
        }
        sendDeltaSync(player, visibleBullets, createdThisTick, playerStates, serverLevel, styleDataCache);
    }

    /**
     * Determines whether a force-full-sync is due for the given player this
     * tick, and records the tick if so.
     *
     * @param player       the player to check
     * @param currentTick  the current level game time
     * @return {@code true} if a full-sync should be sent this tick
     */
    private static boolean shouldForceFullSync(ServerPlayer player, long currentTick) {
        Long lastSync = LAST_FORCE_FULL_SYNC_TICK.get(player);
        if (lastSync == null
                || currentTick - lastSync >= ModularShootCommonConfig.getFullSyncIntervalTicks()) {
            LAST_FORCE_FULL_SYNC_TICK.put(player, currentTick);
            return true;
        }
        return false;
    }

    /**
     * Sends a force-full-sync packet: all visible bullets as
     * {@link BulletS2CPacket.FullBulletEntry}, and resets the player's
     * client state to match.
     *
     * <p>Short-life bullets created this tick (including those already
     * removed by collision) are included first so the D-03 guarantee holds
     * even when a force-full-sync happens to fall on the same tick.</p>
     *
     * @param player         the player to sync to
     * @param visibleBullets the spatial-index-filtered bullets visible to this
     *                       player (candidate subset); anything outside this
     *                       subset is treated as removed for this player
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param playerStates   the player's per-bullet sync state (cleared and rebuilt)
     * @param serverLevel    the server level (for gun-registry lookups)
     * @param styleDataCache the per-tick full-entry conversion cache shared by all players
     */
    private static void sendForceFullSync(
            ServerPlayer player,
            Collection<BulletRecord> visibleBullets,
            List<BulletRecord> createdThisTick,
            Map<Integer, BulletState> playerStates,
            ServerLevel serverLevel,
            Map<Integer, BulletStyleData> styleDataCache) {
        double syncRadius = getSyncRadius(player);
        long currentTick = serverLevel.getGameTime();
        List<BulletS2CPacket.FullBulletEntry> entries = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        playerStates.clear();
        // Force-full-sync always attaches the full style payload (审查修复:
        // 丢包恢复). If the first full-style transmission for a style id was
        // dropped, the same id could still be referenced by a style-less entry
        // while the client has no cached copy — so every full-sync re-sends the
        // full payload unconditionally, letting the client self-heal rather than
        // getting stuck on a missing style cache entry forever.
        collectCreatedThisTick(createdThisTick, player, syncRadius, serverLevel, currentTick,
                entries, playerStates, seenIds, styleDataCache, true);
        collectActiveFullEntries(visibleBullets, player, syncRadius, serverLevel, currentTick,
                entries, playerStates, seenIds, styleDataCache, true);
        PacketDistributor.sendToPlayer(player, BulletS2CPacket.fullSync(entries));
    }

    /**
     * Adds all visible active bullets as full entries to the list, skipping
     * ids already present in {@code seenIds} (e.g. from created-this-tick).
     *
     * @param visibleBullets   the spatial-index-filtered bullets visible to
     *                         this player (candidate subset)
     * @param player       the player to sync to
     * @param syncRadius   the cull radius in blocks
     * @param serverLevel  the server level (for gun-registry lookups)
     * @param currentTick  the current server game time (ticks); recorded as
     *                     the last-sent tick for each bullet's state
     * @param entries      the full-entry list to populate
     * @param playerStates the player's per-bullet sync state (updated)
     * @param seenIds      ids already added (updated)
     * @param styleDataCache the per-tick full-entry conversion cache shared by all players
     */
    private static void collectActiveFullEntries(
            Collection<BulletRecord> visibleBullets,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            long currentTick,
            List<BulletS2CPacket.FullBulletEntry> entries,
            Map<Integer, BulletState> playerStates,
            Set<Integer> seenIds,
            Map<Integer, BulletStyleData> styleDataCache,
            boolean forceAttachStyle) {
        for (BulletRecord bullet : visibleBullets) {
            int bulletId = bullet.getBulletId();
            if (seenIds.contains(bulletId) || !isInRenderDistance(bullet, player, syncRadius)) {
                continue;
            }
            entries.add(toFullBulletEntry(bullet, serverLevel, player, styleDataCache, forceAttachStyle));
            playerStates.put(bulletId, toBulletState(bullet, currentTick));
            seenIds.add(bulletId);
        }
    }

    /**
     * Sends an incremental delta packet: new bullets (including this tick's
     * short-life creations), updated bullets, and removed bullet ids.
     *
     * @param player         the player to sync to
     * @param visibleBullets the spatial-index-filtered bullets visible to this
     *                       player (candidate subset); anything outside this
     *                       subset is treated as removed for this player
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param playerStates   the player's per-bullet sync state (updated in place)
     * @param serverLevel    the server level (for gun-registry lookups)
     * @param styleDataCache the per-tick full-entry conversion cache shared by all players
     */
    private static void sendDeltaSync(
            ServerPlayer player,
            Collection<BulletRecord> visibleBullets,
            List<BulletRecord> createdThisTick,
            Map<Integer, BulletState> playerStates,
            ServerLevel serverLevel,
            Map<Integer, BulletStyleData> styleDataCache) {
        double syncRadius = getSyncRadius(player);
        long currentTick = serverLevel.getGameTime();

        // Nothing to diff and no client state to clean up — skip the
        // per-player collection allocations entirely (no bullets, no
        // created-this-tick, and the player has no tracked bullet state).
        if (visibleBullets.isEmpty()
                && (createdThisTick == null || createdThisTick.isEmpty())
                && playerStates.isEmpty()) {
            return;
        }
        Set<Integer> createdIds = new HashSet<>();
        List<BulletS2CPacket.FullBulletEntry> newBullets = new ArrayList<>();
        List<BulletS2CPacket.DeltaBulletEntry> updatedBullets = new ArrayList<>();
        List<Integer> removedBulletIds = new ArrayList<>();

        // 1. Short-life guarantee: this tick's creations get full entries
        //    even if already removed by collision (D-03).
        collectCreatedThisTick(createdThisTick, player, syncRadius, serverLevel, currentTick,
                newBullets, playerStates, createdIds, styleDataCache, false);

        // 2. Diff active bullets against client state.
        Set<Integer> activeIds = collectActiveBulletDeltas(
                visibleBullets, player, syncRadius, serverLevel, currentTick,
                newBullets, updatedBullets, playerStates, createdIds, styleDataCache, false);

        // 3. Removed bullets: in client state but no longer active and not
        //    just created this tick.
        collectRemovedBullets(playerStates, activeIds, createdIds, removedBulletIds);

        if (newBullets.isEmpty() && updatedBullets.isEmpty() && removedBulletIds.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                BulletS2CPacket.delta(newBullets, updatedBullets, removedBulletIds));
    }

    /**
     * Adds this tick's newly created bullets to the {@code newBullets} list
     * with full entries, records their state, and tracks their ids so they
     * are not double-counted as removed.
     *
     * @param createdThisTick the created-this-tick list, or {@code null}
     * @param player          the player to sync to
     * @param syncRadius      the cull radius in blocks
     * @param serverLevel     the server level (for gun-registry lookups)
     * @param currentTick     the current server game time (ticks); recorded as
     *                        the last-sent tick for each bullet's state
     * @param newBullets      the new-bullets bucket to populate
     * @param playerStates    the player's per-bullet sync state (updated)
     * @param createdIds      the set of created-this-tick ids (populated)
     * @param styleDataCache  the per-tick full-entry conversion cache shared by all players
     */
    private static void collectCreatedThisTick(
            List<BulletRecord> createdThisTick,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            long currentTick,
            List<BulletS2CPacket.FullBulletEntry> newBullets,
            Map<Integer, BulletState> playerStates,
            Set<Integer> createdIds,
            Map<Integer, BulletStyleData> styleDataCache,
            boolean forceAttachStyle) {
        if (createdThisTick == null) {
            return;
        }
        for (BulletRecord bullet : createdThisTick) {
            if (!isInRenderDistance(bullet, player, syncRadius)) {
                continue;
            }
            int bulletId = bullet.getBulletId();
            newBullets.add(toFullBulletEntry(bullet, serverLevel, player, styleDataCache, forceAttachStyle));
            playerStates.put(bulletId, toBulletState(bullet, currentTick));
            createdIds.add(bulletId);
        }
    }

    /**
     * Diffs active bullets against the player's client state, adding new
     * bullets (full entry) and changed bullets (delta entry) to the
     * appropriate buckets.
     *
     * @param visibleBullets    the spatial-index-filtered bullets visible to
     *                          this player (candidate subset)
     * @param player        the player to sync to
     * @param syncRadius    the cull radius in blocks
     * @param serverLevel   the server level (for gun-registry lookups)
     * @param currentTick   the current server game time (ticks) — used both to
     *                      enforce the distance-band update frequency and to
     *                      record the last-sent tick when a delta is emitted
     * @param newBullets    the new-bullets bucket to populate
     * @param updatedBullets the updated-bullets bucket to populate
     * @param playerStates  the player's per-bullet sync state (updated)
     * @param createdIds    ids already handled via created-this-tick
     * @param styleDataCache the per-tick full-entry conversion cache shared by all players
     * @return the set of active bullet ids visible to the player
     */
    private static Set<Integer> collectActiveBulletDeltas(
            Collection<BulletRecord> visibleBullets,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            long currentTick,
            List<BulletS2CPacket.FullBulletEntry> newBullets,
            List<BulletS2CPacket.DeltaBulletEntry> updatedBullets,
            Map<Integer, BulletState> playerStates,
            Set<Integer> createdIds,
            Map<Integer, BulletStyleData> styleDataCache,
            boolean forceAttachStyle) {
        Set<Integer> activeIds = new HashSet<>();
        for (BulletRecord bullet : visibleBullets) {
            int bulletId = bullet.getBulletId();
            if (createdIds.contains(bulletId)) {
                continue; // handled (and state-recorded) by collectCreatedThisTick
            }
            if (!isInRenderDistance(bullet, player, syncRadius)) {
                // Out of range: not active for this player. collectRemovedBullets
                // drops it from playerStates and notifies the client, which only
                // destroys render objects via removedBulletIds/full-sync.
                continue;
            }
            // A bullet inside the visible subset stays "active" (never removed)
            // even when this tick's update is throttled, so a skipped delta
            // cannot make the client destroy the bullet's render object.
            activeIds.add(bulletId);
            BulletState prevState = playerStates.get(bulletId);
            if (prevState == null) {
                newBullets.add(toFullBulletEntry(bullet, serverLevel, player, styleDataCache, forceAttachStyle));
                playerStates.put(bulletId, toBulletState(bullet, currentTick));
            } else if (stateChanged(bullet, prevState)
                    && BulletDeltaQuantizer.isUpdateEligible(
                            horizontalDistanceToPlayer(bullet, player),
                            currentTick, prevState.lastSentTick(),
                            ModularShootCommonConfig.getCloseDistance(),
                            ModularShootCommonConfig.getMidDistance(),
                            ModularShootCommonConfig.getMidIntervalTicks(),
                            ModularShootCommonConfig.getFarIntervalTicks())) {
                updatedBullets.add(toDeltaBulletEntry(bullet));
                playerStates.put(bulletId, toBulletState(bullet, currentTick));
            }
        }
        return activeIds;
    }

    /**
     * Collects bullet ids that are in the player's client state but no
     * longer in the player's <em>visible subset</em> (and not just created
     * this tick) into the removed-bullets bucket, and removes them from the
     * client state. A bullet outside the visible subset — whether because it
     * was actually removed or because it left the player's render radius — is
     * treated identically as removed: the client destroys its render object.
     *
     * @param playerStates     the player's per-bullet sync state (pruned)
     * @param activeIds        ids of bullets still in this player's visible subset
     * @param createdIds       ids created this tick (excluded from removal)
     * @param removedBulletIds the removed-bullets bucket to populate
     */
    private static void collectRemovedBullets(
            Map<Integer, BulletState> playerStates,
            Set<Integer> activeIds,
            Set<Integer> createdIds,
            List<Integer> removedBulletIds) {
        Iterator<Map.Entry<Integer, BulletState>> it = playerStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, BulletState> entry = it.next();
            int bulletId = entry.getKey();
            if (!activeIds.contains(bulletId) && !createdIds.contains(bulletId)) {
                removedBulletIds.add(bulletId);
                it.remove();
            }
        }
    }

    // --- Culling & state helpers ----------------------------------------

    /**
     * Returns whether the bullet is within the player's sync radius, using
     * Chebyshev (chessboard) distance — {@code max(|dx|, |dz|)} — rather than
     * Euclidean distance.
     *
     * <p>The Chebyshev metric matches the square shape of the chunk tracking
     * view: a player's visible area is a square of chunks centered on their
     * chunk, not a circle. Using Euclidean distance would cull bullets at the
     * corners of the square that are still within the player's render
     * distance, causing bullets to pop in/out at diagonal directions. The
     * vertical (y) axis is intentionally excluded because the sync radius is
     * derived from the horizontal chunk view distance and players can see
     * bullets far above or below them within the same chunk column.</p>
     *
     * @param bullet      the bullet to test
     * @param player      the player whose position is the cull center
     * @param syncRadius  the cull radius in blocks
     * @return {@code true} if the bullet is within the Chebyshev radius
     */
    private static boolean isInRenderDistance(BulletRecord bullet, ServerPlayer player, double syncRadius) {
        Vec3 playerPos = player.position();
        Vec3 bulletPos = bullet.getPosition();
        double dx = Math.abs(playerPos.x - bulletPos.x);
        double dz = Math.abs(playerPos.z - bulletPos.z);
        return Math.max(dx, dz) <= syncRadius;
    }

    /**
     * Returns the bullet's horizontal (x-z) Euclidean distance to the player
     * in blocks, used by the distance-band update-frequency decimation
     * ({@link BulletDeltaQuantizer}). Unlike culling (which uses the Chebyshev
     * metric to match the square chunk-view), decimation is driven by the
     * natural visual distance, so the mid/far band thresholds read as real
     * block distances.
     *
     * @param bullet the bullet to measure
     * @param player the player at the centre
     * @return the horizontal Euclidean distance in blocks
     */
    private static double horizontalDistanceToPlayer(BulletRecord bullet, ServerPlayer player) {
        Vec3 playerPos = player.position();
        Vec3 bulletPos = bullet.getPosition();
        double dx = playerPos.x - bulletPos.x;
        double dz = playerPos.z - bulletPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Returns whether the bullet's position or direction has changed on the
     * fixed-point grid since the last sync, per {@link BulletDeltaQuantizer}
     * (阶段 2 / 任务 2.2). Sub-quantum (sub-pixel) drift does not count as a
     * change, so it stops spamming delta entries every tick.
     *
     * @param bullet     the current bullet state
     * @param prevState  the last-synced state (full-precision comparison base)
     * @return {@code true} if a delta entry should be considered for sending
     */
    private static boolean stateChanged(BulletRecord bullet, BulletState prevState) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return BulletDeltaQuantizer.positionChanged(
                        pos.x, pos.y, pos.z,
                        prevState.posX(), prevState.posY(), prevState.posZ())
                || BulletDeltaQuantizer.directionChanged(
                        dir.x, dir.y, dir.z,
                        prevState.dirX(), prevState.dirY(), prevState.dirZ());
    }

    // --- Entry conversion -----------------------------------------------

    /**
     * Converts a bullet into a per-player {@link BulletS2CPacket.FullBulletEntry}
     * with content-addressed visual style (阶段 2 / 任务 2.3).
     *
     * <p>The flight-invariant style payload ({@link BulletStyleData}) is built
     * at most once per bullet per tick into {@code styleDataCache}. The stable
     * style wire id comes from {@link #STYLE_ADDRESSER}; the full style payload
     * is attached only when the receiving player does not yet know that style id
     * (tracked in {@link #PLAYER_KNOWN_STYLE_IDS}).</p>
     *
     * <p><b>Re-transmission guarantee (审查修复).</b> When
     * {@code forceAttachStyle} is {@code true} (the force-full-sync path) the
     * full payload is attached <em>unconditionally</em>, regardless of the
     * player's known-set. Marking the player's known-set on send is only a
     * bandwidth heuristic for incremental deltas: if the very first full-style
     * delta packet is dropped, the next force-full-sync re-sends the full
     * payload so the client can heal its style cache instead of being stuck on
     * a missing style id forever. Delta packets reference the known-set and are
     * allowed to carry only the id; a dropped delta is repaired by the next
     * full-sync.</p>
     *
     * @param bullet        the bullet record to convert
     * @param level         the server level (only for entity-id lookups now)
     * @param player        the player this entry is being built for
     * @param styleDataCache the per-tick style-payload cache, keyed by bullet id
     * @param forceAttachStyle whether to unconditionally attach the full style
     *                        payload ({@code true} on force-full-sync)
     * @return a full bullet entry carrying the style id and (when needed) the
     *         full style payload
     */
    private static BulletS2CPacket.FullBulletEntry toFullBulletEntry(
            BulletRecord bullet, Level level, ServerPlayer player,
            Map<Integer, BulletStyleData> styleDataCache, boolean forceAttachStyle) {
        BulletStyleData styleData = styleDataCache.computeIfAbsent(
                bullet.getBulletId(), id -> buildBulletStyleData(bullet, level));
        String fingerprint = BulletStyleFingerprint.of(styleData);
        Integer existingId = STYLE_ADDRESSER.peekId(fingerprint);
        boolean clientKnows = !forceAttachStyle && existingId != null
                && PLAYER_KNOWN_STYLE_IDS.computeIfAbsent(player, k -> new HashSet<>()).contains(existingId);
        BulletStyleContentAddresser.BulletStyleRef ref =
                STYLE_ADDRESSER.resolve(styleData, fingerprint, clientKnows);
        if (ref.hasFullStyle()) {
            PLAYER_KNOWN_STYLE_IDS.computeIfAbsent(player, k -> new HashSet<>()).add(ref.styleId());
        }
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return new BulletS2CPacket.FullBulletEntry(
                bullet.getBulletId(),
                pos.x, pos.y, pos.z,
                dir.x, dir.y, dir.z,
                resolveShooterEntityId(bullet, level),
                toClientBulletSnapshot(bullet.getSnapshot()),
                ref.styleId(),
                ref.style(),
                BulletSyncExtraRegistry.collect(bullet));
    }

    /**
     * Builds the content-addressed flight-invariant <em>visual</em> style
     * payload {@link BulletStyleData} for a bullet: the composed visual style
     * (texture / model / render mode / scale / tint / attach layers).
     * Player-independent and shared per tick via {@code styleDataCache}. The
     * per-bullet shooter and the stats/traits snapshot are deliberately
     * excluded (审查 E5 / 审查修复): the shooter is carried inline by
     * {@link BulletS2CPacket.FullBulletEntry#shooterEntityId()} and the
     * snapshot by {@link BulletS2CPacket.FullBulletEntry#snapshot()}, so the
     * same visual style can be shared across different shooters and
     * stat-varying bullets via one wire id.
     *
     * <p>As of the modifier-stacking redesign (设计规格 §2.1 / §4.3), the visual
     * style is <em>cached</em> on the {@link BulletRecord} by whichever
     * bullet-creation call site invoked {@link
     * org.yanbwe.modularshoot.bullet.VisualCompositionService#compose}. This
     * method does <b>not</b> re-resolve the visual style from the gun registry —
     * it re-reads the same frozen value. In-flight appearance mutations (e.g.
     * {@code onVisualTick} hooks) operate on the client-side
     * {@code BulletRenderObject} directly and never re-flow through this method
     * (spec §7.2).</p>
     *
     * @param bullet the bullet record to convert
     * @param level  the server level (only for entity-id lookups now)
     * @return the flight-invariant style payload
     */
    private static BulletStyleData buildBulletStyleData(BulletRecord bullet, Level level) {
        ComposedBulletStyle composed = bullet.getComposedStyle();
        // base texture/model: only one is non-null per render mode.
        BulletStyle.RenderMode baseMode = composed.base().renderMode();
        @Nullable ResourceLocation baseTexture =
                baseMode == BulletStyle.RenderMode.BILLBOARD ? composed.base().texture().orElse(null) : null;
        @Nullable ResourceLocation baseModel =
                baseMode == BulletStyle.RenderMode.THREE_D ? composed.base().model().orElse(null) : null;
        // composedTint: collapse the white identity tint to a null wire sentinel
        // (spec §4.3 — saves 4 floats per default-styled bullet on the wire).
        @Nullable Vector4f composedTint = isWhite(composed.composedTint()) ? null : composed.composedTint();
        return new BulletStyleData(
                baseTexture, baseModel, baseMode.getSerializedName(),
                composed.renderScale(),
                composedTint,
                composed.layers().stream()
                        .map(l -> new BulletS2CPacket.FullBulletEntry.LayerEntryFull(
                                l.renderMode().getSerializedName(),
                                l.renderMode() == BulletStyle.RenderMode.BILLBOARD ? l.texture() : null,
                                l.renderMode() == BulletStyle.RenderMode.THREE_D ? l.model() : null,
                                l.followRotation(), l.followScale(),
                                l.offsetX(), l.offsetY(), l.offsetZ(),
                                l.scale(),
                                l.tint().x, l.tint().y, l.tint().z, l.tint().w))
                        .toList());
    }

    /**
     * Returns whether the given tint is the white identity tint
     * {@code (1,1,1,1)}, the condition the wire codec collapses to a
     * {@code null} sentinel.
     *
     * @param t the tint to test (may be {@code null} but in practice never
     *          is — {@link ComposedBulletStyle#composedTint} is never null)
     * @return {@code true} if the tint equals white identity
     */
    private static boolean isWhite(@Nullable Vector4f t) {
        return t != null
                && t.x == 1.0f && t.y == 1.0f && t.z == 1.0f && t.w == 1.0f;
    }

    /**
     * Builds a {@link ClientBulletSnapshot} — the client-side projection of
     * the bullet's frozen stats/traits — from the server-side
     * {@link BulletSnapshot} (设计文档 §特性视觉钩子, line 1298).
     *
     * <p><b>Shooter is excluded (审查修复).</b> The shooter is a per-bullet
     * dynamic identity carried inline by
     * {@link BulletS2CPacket.FullBulletEntry#shooterEntityId()}, not part of
     * the content-addressed flight-invariant style payload. It is therefore
     * omitted from the snapshot so that bullets sharing one visual style across
     * different shooters map to the <em>same</em> style fingerprint and share a
     * single wire id ({@link BulletStyleFingerprint} no longer hashes it). Any
     * client that needs owner attribution reads {@code shooterEntityId()} from
     * the full entry.</p>
     *
     * <p>The full stats and traits maps are synced because the framework
     * cannot predict which attributes third-party visual hooks will read
     * (a mod may drive appearance from any stat or trait). The maps are
     * defensively copied by {@link ClientBulletSnapshot}'s compact
     * constructor, so later server-side hook mutations do not leak into the
     * already-serialised packet. The damage-type holder and per-bullet state
     * map are omitted: they are server-only and never consumed by
     * {@code onVisualTick}.</p>
     *
     * @param snapshot the bullet's frozen server-side snapshot
     * @return a client-safe snapshot projection ready for serialisation
     */
    private static ClientBulletSnapshot toClientBulletSnapshot(BulletSnapshot snapshot) {
        return new ClientBulletSnapshot(
                snapshot.getStats(),
                snapshot.getTraits(),
                snapshot.getGunId(),
                null);
    }

    /**
     * Converts a {@link BulletRecord} into a position/direction-only
     * {@link BulletS2CPacket.DeltaBulletEntry} for incremental sync.
     *
     * @param bullet the bullet record to convert
     * @return a delta bullet entry carrying only id + position + direction
     */
    private static BulletS2CPacket.DeltaBulletEntry toDeltaBulletEntry(BulletRecord bullet) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return new BulletS2CPacket.DeltaBulletEntry(
                bullet.getBulletId(),
                pos.x, pos.y, pos.z,
                dir.x, dir.y, dir.z,
                BulletSyncExtraRegistry.collect(bullet));
    }

    /**
     * Captures the current position/direction of a bullet into a
     * {@link BulletState} for client-state tracking.
     *
     * @param bullet   the bullet record to snapshot
     * @param sendTick the server game time (ticks) at which this state is
     *                 being recorded (the last actual send tick)
     * @return a snapshot of the bullet's current position/direction and the
     *         send tick
     */
    private static BulletState toBulletState(BulletRecord bullet, long sendTick) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return new BulletState(pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, sendTick);
    }

    /**
     * Resolves the shooter's network entity id from the bullet record's
     * shooter uuid, looking the entity up in the server level.
     *
     * @param bullet the bullet record carrying the shooter uuid
     * @param level  the server level for entity lookup
     * @return the shooter's network entity id, or {@link #NO_SHOOTER} when
     *         ownerless or the entity is no longer present
     */
    private static int resolveShooterEntityId(BulletRecord bullet, Level level) {
        UUID shooterUuid = bullet.getShooter();
        if (shooterUuid == null) {
            return NO_SHOOTER;
        }
        if (level instanceof ServerLevel serverLevel) {
            Entity entity = serverLevel.getEntity(shooterUuid);
            return entity != null ? entity.getId() : NO_SHOOTER;
        }
        return NO_SHOOTER;
    }

    // --- Render distance (D-04) -----------------------------------------

    /**
     * Returns the per-player sync radius in blocks, derived from the
     * player's chunk tracking view — which reflects the client's configured
     * render distance clamped by the server view distance
     * (设计文档 §同步范围 line 1273). Each chunk is 16 blocks wide.
     *
     * <p>Falls back to the server's view distance when the tracking view is
     * not a {@link ChunkTrackingView.Positioned} (e.g. during dimension
     * transition when the view is {@link ChunkTrackingView#EMPTY}).</p>
     *
     * @param player the player whose render distance determines the radius
     * @return the sync radius in blocks
     */
    private static double getSyncRadius(ServerPlayer player) {
        ChunkTrackingView view = player.getChunkTrackingView();
        if (view instanceof ChunkTrackingView.Positioned positioned) {
            return positioned.viewDistance() * 16.0;
        }
        // Fallback: server view distance (used during dimension transition
        // when the tracking view is EMPTY).
        return player.getServer().getPlayerList().getViewDistance() * 16.0;
    }

    // --- Internal records ------------------------------------------------

    /**
     * Last-synced position/direction for a single bullet id, plus the server
     * tick the last delta was actually sent, used to compute the
     * new/updated/removed diff each tick. The position/direction keep full
     * server precision as the comparison base (阶段 2 / 任务 2.2); the
     * {@link BulletDeltaQuantizer} decides whether the quantized grid changed
     * and whether the distance band allows this tick's update.
     *
     * @param posX        last-synced world-space x (full precision)
     * @param posY        last-synced world-space y (full precision)
     * @param posZ        last-synced world-space z (full precision)
     * @param dirX        last-synced direction x
     * @param dirY        last-synced direction y
     * @param dirZ        last-synced direction z
     * @param lastSentTick the server game time (ticks) when the last delta was
     *                     actually sent for this bullet to this player; used by
     *                     the distance-band frequency decimation
     */
    private record BulletState(
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ,
            long lastSentTick) {
    }
}
