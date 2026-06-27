package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.client.ClientBulletSnapshot;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

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

import org.jetbrains.annotations.Nullable;

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
 *   <li>changed bullets (position/direction moved beyond epsilon) →
 *       {@link BulletS2CPacket.DeltaBulletEntry}</li>
 *   <li>removed bullets (in client state but no longer active) →
 *       {@link BulletS2CPacket#removedBulletIds()}</li>
 * </ul>
 *
 * <h2>Force-full-sync (drift recovery)</h2>
 * <p>Every {@link #FULL_SYNC_INTERVAL_TICKS} ticks the service sends a
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
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletSyncService {

    /** Attribute id for {@code bullet_size}, read from each bullet's frozen snapshot. */
    private static final ResourceLocation BULLET_SIZE_ID =
            ModularShootAttributes.BULLET_SIZE.getKey().location();

    /** Render-mode tag for the billboard pipeline, used as the default when no style is defined. */
    private static final String DEFAULT_RENDER_MODE = BulletStyle.RenderMode.BILLBOARD.getSerializedName();

    /** Sentinel entity id used when the bullet has no shooter (ownerless independent firing). */
    private static final int NO_SHOOTER = -1;

    /**
     * Interval (in ticks) between forced full-sync packets, used to recover
     * from dropped delta packets and prevent client state drift. 100 ticks =
     * 5 seconds.
     */
    private static final long FULL_SYNC_INTERVAL_TICKS = 100L;

    /**
     * Floating-point comparison epsilon for position/direction change
     * detection. Values smaller than this are treated as unchanged to avoid
     * spamming delta entries for sub-pixel jitter.
     */
    private static final double STATE_EPSILON = 1.0e-6;

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
     * {@code currentTick - lastForceTick >= FULL_SYNC_INTERVAL_TICKS} a
     * full-sync packet is sent instead of a delta packet.
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
     * Collects all active bullets, drains the created-this-tick list, and
     * dispatches a per-player {@link BulletS2CPacket} to each player in the
     * dimension.
     *
     * @param level the server level whose bullets are being synced
     */
    private static void syncBulletsToPlayers(Level level) {
        BulletManager manager = BulletManager.get(level);
        Collection<BulletRecord> allBullets = manager.getAllBullets();

        ServerLevel serverLevel = (ServerLevel) level;
        List<ServerPlayer> players = serverLevel.players();
        // Drain created-this-tick regardless of player count so the list
        // does not leak into the next tick when no players are online.
        List<BulletRecord> createdThisTick = CREATED_THIS_TICK.remove(level);
        if (players.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            syncPlayerBullets(player, allBullets, createdThisTick, serverLevel);
        }
    }

    /**
     * Builds and sends a per-player {@link BulletS2CPacket} — either a
     * force-full-sync (drift recovery / initial) or an incremental delta.
     *
     * @param player         the player to sync to
     * @param allBullets     every active bullet in the dimension
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param serverLevel    the server level (for gun-registry lookups and tick time)
     */
    private static void syncPlayerBullets(
            ServerPlayer player,
            Collection<BulletRecord> allBullets,
            List<BulletRecord> createdThisTick,
            ServerLevel serverLevel) {
        Map<Integer, BulletState> playerStates =
                CLIENT_STATES.computeIfAbsent(player, k -> new HashMap<>());
        long currentTick = serverLevel.getGameTime();
        if (shouldForceFullSync(player, currentTick)) {
            sendForceFullSync(player, allBullets, createdThisTick, playerStates, serverLevel);
            return;
        }
        sendDeltaSync(player, allBullets, createdThisTick, playerStates, serverLevel);
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
        if (lastSync == null || currentTick - lastSync >= FULL_SYNC_INTERVAL_TICKS) {
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
     * @param allBullets     every active bullet in the dimension
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param playerStates   the player's per-bullet sync state (cleared and rebuilt)
     * @param serverLevel    the server level (for gun-registry lookups)
     */
    private static void sendForceFullSync(
            ServerPlayer player,
            Collection<BulletRecord> allBullets,
            List<BulletRecord> createdThisTick,
            Map<Integer, BulletState> playerStates,
            ServerLevel serverLevel) {
        double syncRadius = getSyncRadius(player);
        List<BulletS2CPacket.FullBulletEntry> entries = new ArrayList<>();
        Set<Integer> seenIds = new HashSet<>();
        playerStates.clear();
        collectCreatedThisTick(createdThisTick, player, syncRadius, serverLevel,
                entries, playerStates, seenIds);
        collectActiveFullEntries(allBullets, player, syncRadius, serverLevel,
                entries, playerStates, seenIds);
        PacketDistributor.sendToPlayer(player, BulletS2CPacket.fullSync(entries));
    }

    /**
     * Adds all visible active bullets as full entries to the list, skipping
     * ids already present in {@code seenIds} (e.g. from created-this-tick).
     *
     * @param allBullets   every active bullet in the dimension
     * @param player       the player to sync to
     * @param syncRadius   the cull radius in blocks
     * @param serverLevel  the server level (for gun-registry lookups)
     * @param entries      the full-entry list to populate
     * @param playerStates the player's per-bullet sync state (updated)
     * @param seenIds      ids already added (updated)
     */
    private static void collectActiveFullEntries(
            Collection<BulletRecord> allBullets,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            List<BulletS2CPacket.FullBulletEntry> entries,
            Map<Integer, BulletState> playerStates,
            Set<Integer> seenIds) {
        for (BulletRecord bullet : allBullets) {
            int bulletId = bullet.getBulletId();
            if (seenIds.contains(bulletId) || !isInRenderDistance(bullet, player, syncRadius)) {
                continue;
            }
            entries.add(toFullBulletEntry(bullet, serverLevel));
            playerStates.put(bulletId, toBulletState(bullet));
            seenIds.add(bulletId);
        }
    }

    /**
     * Sends an incremental delta packet: new bullets (including this tick's
     * short-life creations), updated bullets, and removed bullet ids.
     *
     * @param player         the player to sync to
     * @param allBullets     every active bullet in the dimension
     * @param createdThisTick bullets created this tick (short-life guarantee), or {@code null}
     * @param playerStates   the player's per-bullet sync state (updated in place)
     * @param serverLevel    the server level (for gun-registry lookups)
     */
    private static void sendDeltaSync(
            ServerPlayer player,
            Collection<BulletRecord> allBullets,
            List<BulletRecord> createdThisTick,
            Map<Integer, BulletState> playerStates,
            ServerLevel serverLevel) {
        double syncRadius = getSyncRadius(player);
        Set<Integer> createdIds = new HashSet<>();
        List<BulletS2CPacket.FullBulletEntry> newBullets = new ArrayList<>();
        List<BulletS2CPacket.DeltaBulletEntry> updatedBullets = new ArrayList<>();
        List<Integer> removedBulletIds = new ArrayList<>();

        // 1. Short-life guarantee: this tick's creations get full entries
        //    even if already removed by collision (D-03).
        collectCreatedThisTick(createdThisTick, player, syncRadius, serverLevel,
                newBullets, playerStates, createdIds);

        // 2. Diff active bullets against client state.
        Set<Integer> activeIds = collectActiveBulletDeltas(
                allBullets, player, syncRadius, serverLevel,
                newBullets, updatedBullets, playerStates, createdIds);

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
     * @param newBullets      the new-bullets bucket to populate
     * @param playerStates    the player's per-bullet sync state (updated)
     * @param createdIds      the set of created-this-tick ids (populated)
     */
    private static void collectCreatedThisTick(
            List<BulletRecord> createdThisTick,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            List<BulletS2CPacket.FullBulletEntry> newBullets,
            Map<Integer, BulletState> playerStates,
            Set<Integer> createdIds) {
        if (createdThisTick == null) {
            return;
        }
        for (BulletRecord bullet : createdThisTick) {
            if (!isInRenderDistance(bullet, player, syncRadius)) {
                continue;
            }
            int bulletId = bullet.getBulletId();
            newBullets.add(toFullBulletEntry(bullet, serverLevel));
            playerStates.put(bulletId, toBulletState(bullet));
            createdIds.add(bulletId);
        }
    }

    /**
     * Diffs active bullets against the player's client state, adding new
     * bullets (full entry) and changed bullets (delta entry) to the
     * appropriate buckets.
     *
     * @param allBullets    every active bullet in the dimension
     * @param player        the player to sync to
     * @param syncRadius    the cull radius in blocks
     * @param serverLevel   the server level (for gun-registry lookups)
     * @param newBullets    the new-bullets bucket to populate
     * @param updatedBullets the updated-bullets bucket to populate
     * @param playerStates  the player's per-bullet sync state (updated)
     * @param createdIds    ids already handled via created-this-tick
     * @return the set of active bullet ids visible to the player
     */
    private static Set<Integer> collectActiveBulletDeltas(
            Collection<BulletRecord> allBullets,
            ServerPlayer player,
            double syncRadius,
            ServerLevel serverLevel,
            List<BulletS2CPacket.FullBulletEntry> newBullets,
            List<BulletS2CPacket.DeltaBulletEntry> updatedBullets,
            Map<Integer, BulletState> playerStates,
            Set<Integer> createdIds) {
        Set<Integer> activeIds = new HashSet<>();
        for (BulletRecord bullet : allBullets) {
            int bulletId = bullet.getBulletId();
            activeIds.add(bulletId);
            if (createdIds.contains(bulletId) || !isInRenderDistance(bullet, player, syncRadius)) {
                continue;
            }
            BulletState prevState = playerStates.get(bulletId);
            if (prevState == null) {
                newBullets.add(toFullBulletEntry(bullet, serverLevel));
                playerStates.put(bulletId, toBulletState(bullet));
            } else if (stateChanged(bullet, prevState)) {
                updatedBullets.add(toDeltaBulletEntry(bullet));
                playerStates.put(bulletId, toBulletState(bullet));
            }
        }
        return activeIds;
    }

    /**
     * Collects bullet ids that are in the player's client state but no
     * longer active (and not just created this tick) into the removed-bullets
     * bucket, and removes them from the client state.
     *
     * @param playerStates     the player's per-bullet sync state (pruned)
     * @param activeIds        ids of bullets still active this tick
     * @param createdIds       ids created this tick (excluded from removal)
     * @param removedBulletIds the removed-bullets bucket to populate
     */
    private static void collectRemovedBullets(
            Map<Integer, BulletState> playerStates,
            Set<Integer> activeIds,
            Set<Integer> createdIds,
            List<Integer> removedBulletIds) {
        for (Integer bulletId : new HashSet<>(playerStates.keySet())) {
            if (!activeIds.contains(bulletId) && !createdIds.contains(bulletId)) {
                removedBulletIds.add(bulletId);
                playerStates.remove(bulletId);
            }
        }
    }

    // --- Culling & state helpers ----------------------------------------

    /**
     * Returns whether the bullet is within the player's sync radius.
     *
     * @param bullet      the bullet to test
     * @param player      the player whose position is the cull center
     * @param syncRadius  the cull radius in blocks
     * @return {@code true} if the bullet is within the radius
     */
    private static boolean isInRenderDistance(BulletRecord bullet, ServerPlayer player, double syncRadius) {
        return player.position().distanceToSqr(bullet.getPosition()) <= syncRadius * syncRadius;
    }

    /**
     * Returns whether the bullet's position or direction has changed beyond
     * {@link #STATE_EPSILON} since the last sync.
     *
     * @param bullet     the current bullet state
     * @param prevState  the last-synced state
     * @return {@code true} if a delta entry should be sent
     */
    private static boolean stateChanged(BulletRecord bullet, BulletState prevState) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return Math.abs(pos.x - prevState.posX()) > STATE_EPSILON
                || Math.abs(pos.y - prevState.posY()) > STATE_EPSILON
                || Math.abs(pos.z - prevState.posZ()) > STATE_EPSILON
                || Math.abs(dir.x - prevState.dirX()) > STATE_EPSILON
                || Math.abs(dir.y - prevState.dirY()) > STATE_EPSILON
                || Math.abs(dir.z - prevState.dirZ()) > STATE_EPSILON;
    }

    // --- Entry conversion -----------------------------------------------

    /**
     * Converts a {@link BulletRecord} into a full-data
     * {@link BulletS2CPacket.FullBulletEntry}, resolving visual style from
     * the gun registry and the shooter's network entity id.
     *
     * @param bullet the bullet record to convert
     * @param level  the server level (for gun-registry and entity lookups)
     * @return a full bullet entry ready for serialisation
     */
    private static BulletS2CPacket.FullBulletEntry toFullBulletEntry(BulletRecord bullet, Level level) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        BulletSnapshot snapshot = bullet.getSnapshot();
        VisualStyle style = resolveVisualStyle(snapshot, level);
        float bulletSize = (float) snapshot.getStat(BULLET_SIZE_ID);
        int shooterEntityId = resolveShooterEntityId(bullet, level);
        ClientBulletSnapshot clientSnapshot = toClientBulletSnapshot(snapshot, bullet.getShooter());
        return new BulletS2CPacket.FullBulletEntry(
                bullet.getBulletId(),
                pos.x, pos.y, pos.z,
                dir.x, dir.y, dir.z,
                style.texture(), style.modelLocation(), style.renderMode(),
                bulletSize, shooterEntityId, clientSnapshot);
    }

    /**
     * Builds a {@link ClientBulletSnapshot} — the client-side projection of
     * the bullet's frozen stats/traits and identity — from the server-side
     * {@link BulletSnapshot} (设计文档 §特性视觉钩子, line 1298).
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
     * @param shooter  the bullet record's shooter uuid (preferred over the
     *                 snapshot's shooter for consistency with
     *                 {@link #resolveShooterEntityId}), or {@code null}
     * @return a client-safe snapshot projection ready for serialisation
     */
    private static ClientBulletSnapshot toClientBulletSnapshot(BulletSnapshot snapshot, @Nullable UUID shooter) {
        return new ClientBulletSnapshot(
                snapshot.getStats(),
                snapshot.getTraits(),
                snapshot.getGunId(),
                shooter);
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
                dir.x, dir.y, dir.z);
    }

    /**
     * Captures the current position/direction of a bullet into a
     * {@link BulletState} for client-state tracking.
     *
     * @param bullet the bullet record to snapshot
     * @return a snapshot of the bullet's current position/direction
     */
    private static BulletState toBulletState(BulletRecord bullet) {
        Vec3 pos = bullet.getPosition();
        Vec3 dir = bullet.getDirection();
        return new BulletState(pos.x, pos.y, pos.z, dir.x, dir.y, dir.z);
    }

    /**
     * Resolves the bullet's visual style (texture, model path, render mode)
     * from the gun definition's {@link BulletStyle}, falling back to a
     * billboard default when the gun or style is absent
     * (设计文档 §子弹视觉样式).
     *
     * @param snapshot the bullet's frozen snapshot carrying the gun id
     * @param level    the server level providing the registry access
     * @return the resolved visual style
     */
    private static VisualStyle resolveVisualStyle(BulletSnapshot snapshot, Level level) {
        ResourceLocation gunId = snapshot.getGunId();
        if (gunId == null) {
            return VisualStyle.DEFAULT;
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(level, gunId);
        if (gunDef.isEmpty()) {
            return VisualStyle.DEFAULT;
        }
        return gunDef.get().bulletStyle()
                .map(BulletSyncService::fromBulletStyle)
                .orElse(VisualStyle.DEFAULT);
    }

    /**
     * Extracts the texture, model path and render-mode tag from a
     * {@link BulletStyle}. The {@code model} map is keyed by render-mode tag
     * ("billboard" → texture, "3d" → model path).
     *
     * @param style the bullet style from the gun definition
     * @return the resolved visual style
     */
    private static VisualStyle fromBulletStyle(BulletStyle style) {
        String renderMode = style.renderMode().getSerializedName();
        ResourceLocation texture = style.model().get(BulletStyle.RenderMode.BILLBOARD.getSerializedName());
        ResourceLocation modelLocation = style.model().get(BulletStyle.RenderMode.THREE_D.getSerializedName());
        return new VisualStyle(texture, modelLocation, renderMode);
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
     * Last-synced position/direction for a single bullet id, used to compute
     * the new/updated/removed diff each tick.
     *
     * @param posX last-synced world-space x
     * @param posY last-synced world-space y
     * @param posZ last-synced world-space z
     * @param dirX last-synced direction x
     * @param dirY last-synced direction y
     * @param dirZ last-synced direction z
     */
    private record BulletState(
            double posX,
            double posY,
            double posZ,
            double dirX,
            double dirY,
            double dirZ) {
    }

    /**
     * Resolved visual style triple carried through the conversion pipeline.
     *
     * @param texture       billboard-mode texture path, or {@code null}
     * @param modelLocation 3d-mode model path, or {@code null}
     * @param renderMode    rendering pipeline tag
     */
    private record VisualStyle(
            ResourceLocation texture,
            ResourceLocation modelLocation,
            String renderMode) {

        /** Default style used when no gun or bullet style is defined. */
        static final VisualStyle DEFAULT = new VisualStyle(null, null, DEFAULT_RENDER_MODE);
    }
}
