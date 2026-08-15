package org.yanbwe.modularshoot.bullet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.render.BulletRenderManager;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.network.BulletSyncService;
import org.yanbwe.modularshoot.trait.RemoveReason;
import org.yanbwe.modularshoot.util.GunResolver;

/**
 * Per-dimension manager for all active bullets (设计文档 §子弹管理器).
 *
 * <p>Bullets are lightweight data records ({@link BulletRecord}) rather than
 * Minecraft entities, so the framework can sustain thousands of in-flight
 * bullets without entity creation/destruction overhead. Each dimension
 * ({@link Level}) owns exactly one {@code BulletManager} instance, obtained via
 * {@link #get(Level)}. Bullets never cross dimensions: a bullet registered to a
 * dimension lives its entire lifetime there (设计文档 §维度边界).</p>
 *
 * <h2>Per-dimension instance storage</h2>
 * <p>Instances are kept in a {@link WeakHashMap} keyed by {@link Level} so that
 * when a dimension unloads and the {@code Level} becomes unreachable the
 * corresponding manager is automatically garbage-collected. The map is wrapped
 * with {@link Collections#synchronizedMap} and the get-or-create path in
 * {@link #get(Level)} synchronizes externally to make the compound operation
 * atomic.</p>
 *
 * <p><b>Important:</b> this class deliberately does <em>not</em> hold a strong
 * reference to the {@link Level}. Storing the {@code Level} would create a
 * value→key strong reference that defeats the {@code WeakHashMap}'s weak key,
 * preventing dimension unload cleanup. Only the lightweight
 * {@link ResourceKey Level dimension key} is retained for logging.</p>
 *
 * <h2>Spatial querying</h2>
 * <p>Collision detection does not iterate every world entity. The
 * {@code BulletTickHandler} maintains a per-tick, per-chunk entity
 * candidate cache (query box + candidate list) and every bullet in a chunk
 * shares one {@code Level.getEntitiesOfClass} query whose box contains its
 * step search box (设计文档 §空间分区).</p>
 *
 * <p>As of 阶段 2 / 任务 2.1 the manager additionally keeps a per-chunk
 * spatial index ({@code long chunk-key → Set<BulletRecord>}, see
 * {@link ChunkPos#asLong(int, int)}) so the network sync path
 * ({@link org.yanbwe.modularshoot.network.BulletSyncService}) can query
 * only the bullets near a player instead of scanning every active bullet each
 * tick (eliminating the {@code O(子弹数 × 玩家数)} scan). The index is kept
 * consistent as bullets move across chunk boundaries via a position listener
 * attached to each registered {@link BulletRecord} (see
 * {@link BulletRecord#setPosition}) and as bullets are removed (see
 * {@link #removeBullet}).
 *
 * <p><b>Concurrency:</b> every index mutation ({@link #addBullet},
 * {@link #removeBullet}, cross-chunk re-bucketing via the position listener)
 * happens on the server's main thread. The {@code ConcurrentHashMap} bucket
 * map plus concurrent inner sets are therefore primarily there so the
 * per-tick <em>read-only</em> sync pass is weakly consistent while the single
 * main-thread tick mutates the index — concurrent <em>writes</em> are not
 * supported.</p>
 *
 * <h2>Bullet id strategy</h2>
 * <p>Ids are {@code int}, per-dimension, monotonically increasing from 1 and
 * never reused. Reuse is avoided so that clients never match a new bullet's
 * spawn packet to a stale destroy packet by id. When the counter approaches
 * {@link Integer#MAX_VALUE} a {@code WARN} is logged and the counter wraps back
 * to 1 (设计文档 §子弹 ID 策略).</p>
 */
public final class BulletManager {

    /** Per-dimension instance registry; weak keys allow unload GC, synchronized for get-or-create. */
    private static final Map<Level, BulletManager> MANAGERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** When the id counter reaches this value a warning is logged and it wraps back to 1. */
    private static final int ID_WRAP_THRESHOLD = Integer.MAX_VALUE - 1000;

    /** All active bullets indexed by id (thread-safe). */
    private final Map<Integer, BulletRecord> bulletsById = new ConcurrentHashMap<>();

    /**
     * Per-chunk spatial index of active bullets: a bullet is present in the
     * bucket of the chunk containing its current position. Buckets are keyed by
     * the packed chunk-coordinate long ({@link ChunkPos#asLong(int, int)}) so
     * the per-tick range query never allocates a {@link ChunkPos} per candidate
     * bucket (阶段 2 / 任务 2.1). Kept consistent by {@link #addBullet},
     * {@link #removeBullet} and the position-change listener attached to each
     * registered {@link BulletRecord}.
     *
     * <p><b>Concurrency:</b> every index mutation ({@link #addBullet},
     * {@link #removeBullet}, cross-chunk re-bucketing) happens on the server's
     * main thread, so the {@code ConcurrentHashMap} + concurrent inner sets are
     * primarily there to make the per-tick <em>read</em> pass (which only
     * iterates the live index and never mutates it) weakly consistent rather
     * than to support concurrent writers.</p>
     */
    private final Map<Long, Set<BulletRecord>> bulletsByChunk =
            new ConcurrentHashMap<>();

    /** Monotonic per-dimension id counter; starts at 1, guarded by synchronization in {@link #nextBulletId}. */
    private int nextId = 1;

    /** Dimension key retained for logging only (never holds a {@link Level} reference). */
    private final ResourceKey<Level> dimensionKey;

    /**
     * Package-private: constructed only via {@link #get(Level)} (production)
     * and directly by the headless unit tests in this package.
     */
    BulletManager(ResourceKey<Level> dimensionKey) {
        this.dimensionKey = dimensionKey;
    }

    /**
     * Returns the {@code BulletManager} for the given dimension, creating it on
     * first access (设计文档 §BulletManager.get).
     *
     * <p>Thread-safe: the get-or-create compound operation is guarded by the
     * synchronized per-dimension map.</p>
     *
     * @param level the dimension to look up (server or client)
     * @return the manager owning all bullets in that dimension
     */
    public static BulletManager get(Level level) {
        synchronized (MANAGERS) {
            BulletManager mgr = MANAGERS.get(level);
            if (mgr == null) {
                mgr = new BulletManager(level.dimension());
                MANAGERS.put(level, mgr);
            }
            return mgr;
        }
    }

    /**
     * Returns the already-existing {@code BulletManager} for the given
     * dimension, or {@code null} if no manager has been created for that
     * dimension yet.
     *
     * <p>Unlike {@link #get(Level)}, this method never creates a new
     * instance. It is intended for lookups that must not have the side effect
     * of instantiating a manager — most notably dimension-unload handling,
     * where creating a manager for an already-unloading dimension would be
     * wasteful and semantically wrong (the manager would be immediately
     * discarded by the {@link WeakHashMap} once the {@link Level} is
     * released).</p>
     *
     * <p>Thread-safe: the lookup is guarded by the same per-dimension map
     * lock as {@link #get(Level)}.</p>
     *
     * @param level the dimension to look up
     * @return the existing manager for that dimension, or {@code null}
     */
    @Nullable
    public static BulletManager getExisting(Level level) {
        synchronized (MANAGERS) {
            return MANAGERS.get(level);
        }
    }

    /**
     * Returns the client-side {@link BulletRenderManager} for the given
     * dimension (设计文档 §渲染对象与渲染管理器, line 1260:
     * "通过 {@code BulletManager.getClientLevel(Level)} 获取").
     *
     * <p>This is the client-side counterpart to {@link #get(Level)}: where
     * {@code get(level)} returns the server-side logical bullet manager
     * (collision, damage, trajectory), this method returns the client-side
     * render manager (pure visual data — render objects, snapshots, visual
     * hooks). The two are deliberately accessed through the same
     * {@code BulletManager} facade so that third-party code has a single
     * entry point for bullet-related lookups, distinguished by side.</p>
     *
     * <p><strong>Client-only.</strong> Calling this method on the server
     * throws {@link IllegalStateException}. The {@link BulletRenderManager}
     * class lives in the {@code client.render} package and is annotated
     * {@code @EventBusSubscriber(Dist.CLIENT)}; although it is present in
     * the main source set (and therefore on the server classpath), it is
     * only functional on the client. The {@code level.isClientSide()} guard
     * ensures the render manager is never touched on the authoritative
     * server.</p>
     *
     * @param level the client dimension to look up the render manager for
     * @return the client-side bullet render manager singleton
     * @throws IllegalStateException if {@code level} is not client-side
     */
    public static BulletRenderManager getClientLevel(Level level) {
        if (level.isClientSide()) {
            return BulletRenderManager.getInstance();
        }
        throw new IllegalStateException("getClientLevel() is client-side only");
    }

    /**
     * Allocates the next bullet id for this dimension. Ids start at 1 and
     * increase monotonically; when the counter nears
     * {@link Integer#MAX_VALUE} a {@code WARN} is logged and it wraps to 1
     * (设计文档 §子弹 ID 策略).
     *
     * <p>The wrap has an extremely small chance of colliding with a still-live
     * bullet id; the design accepts this because the only consequence is a
     * transient client-side visual mismatch, not a server logic error.</p>
     *
     * @return the next unique-per-dimension bullet id
     */
    public synchronized int nextBulletId() {
        int id = nextId;
        if (nextId >= ID_WRAP_THRESHOLD) {
            ModularShoot.LOGGER.warn(
                    "Bullet id counter for dimension {} approaching Integer.MAX_VALUE; wrapping to 1.",
                    dimensionKey.location());
            nextId = 1;
        } else {
            nextId++;
        }
        return id;
    }

    /**
     * Registers a bullet with this dimension's manager (设计文档 §空间分区).
     *
     * <p>The bullet must already carry a unique id assigned via
     * {@link #nextBulletId()} (or equivalent).</p>
     *
     * <p>Registers the bullet in both the id index and the per-chunk spatial
     * index, and attaches a position listener so cross-chunk movement stays
     * indexed (阶段 2 / 任务 2.1).</p>
     *
     * @param bullet the bullet record to register
     */
    public void addBullet(BulletRecord bullet) {
        bulletsById.put(bullet.getBulletId(), bullet);
        // Attach this manager as the spatial-index listener so any later
        // setPosition (tick advance or a trait hook) keeps the chunk buckets
        // consistent, then index the bullet at its initial position.
        bullet.setPositionListener(this::onBulletPositionChanged);
        addToSpatialIndex(bullet, chunkKeyOf(bullet.getPosition()));
    }

    /**
     * Independently fires a bullet from a custom source (turret, trap, boss
     * attack, etc.) without going through the player shooting engine
     * (设计文档 §独立发射).
     *
     * <p>Unlike the player shoot path, this method performs no fire-rate
     * control, no ShootPredicate check, no PreShootEvent/PostShootEvent and
     * no sound playback. The bullet enters the normal tick loop (flight,
     * collision, damage, hooks) once registered.</p>
     *
     * <p><b>Snapshot field conventions for independent firing</b>
     * (设计文档 §独立发射的快照字段约定): the caller is responsible for
     * constructing the snapshot with {@code gunId = null} and
     * {@code gunInstanceUuid = null}. The {@code shooter} field depends on the
     * caller-supplied uuid (null for ownerless sources such as traps).</p>
     *
     * <p>The {@code level} parameter is retained for API consistency with the
     * design document; the manager is already bound to a dimension, so it is
     * not used in the registration logic. It is, however, passed to
     * {@link BulletSyncService#markBulletCreated} so the tick-end sync
     * includes this bullet even if it is removed by collision in the same
     * Pre step (设计文档 §短寿命子弹保证). Callers must ensure the passed
     * level matches this manager's dimension.</p>
     *
     * @param level     the dimension to fire into (must match this manager's dimension)
     * @param position  the launch position
     * @param direction the initial flight direction (normalized)
     * @param snapshot  the bullet snapshot (caller-constructed; gunId/gunInstanceUuid should be null)
     * @param shooter   the shooter uuid, or {@code null} for ownerless sources
     * @return the newly created and registered BulletRecord
     */
    public BulletRecord fireBullet(
            Level level,
            Vec3 position,
            Vec3 direction,
            BulletSnapshot snapshot,
            @Nullable UUID shooter) {
        int bulletId = nextBulletId();
        // Compose the visual style exactly once at creation (设计规格 §2.1
        // "创建瞬间冻结"). For the public API entry point, gunData is
        // reverse-looked-up from the snapshot's gun instance uuid (spec §4.1:
        // "gunData 通过 snapshot.gunInstanceUuid 反查取得, state 初值从
        // BulletSnapshot.state map 取"). When no firing gun is resolvable
        // (e.g. independent turret/trap firing with a null gun instance uuid),
        // gunData stays null and the composition degrades gracefully to the
        // framework FALLBACK_BASE / white tint / empty layers.
        @Nullable GunData gunData = null;
        ItemStack gunStack = GunResolver.resolveGunFromSnapshot(snapshot, level);
        if (gunStack != null) {
            gunData = gunStack.get(ModularShootDataComponents.GUN_DATA.get());
        }
        ComposedBulletStyle composed = VisualCompositionService.INSTANCE.compose(
                level.registryAccess(), snapshot, gunData);
        BulletRecord bullet = new BulletRecord(snapshot, shooter, position, direction, bulletId, composed);
        addBullet(bullet);
        // Mark the new bullet as created this tick so that the tick-end sync
        // includes it in the newBullets bucket — even if the bullet is removed
        // by collision before the Post tick event fires (设计文档 §短寿命子弹保证).
        // Server-only guard: BulletSyncService is server-side and the
        // created-this-tick list is only drained from the server's
        // LevelTickEvent.Post handler, so marking on the client would leak.
        if (!level.isClientSide()) {
            BulletSyncService.markBulletCreated(level, bullet);
        }
        return bullet;
    }

    /**
     * Removes a bullet from this manager by id. The trait
     * {@code ON_REMOVE} hook is fired before the bullet is fully evicted so
     * that callbacks observe a valid record with its snapshot intact
     * (设计文档 §onRemove — 在移除前触发).
     *
     * <p>The bullet is first atomically detached from the id index (which
     * guarantees the hook fires exactly once even under concurrent
     * removal), then the {@code ON_REMOVE} callbacks run. The
     * {@code ON_EXPIRE} hook — when applicable — is fired by the caller
     * before invoking this method (设计文档 §onExpire 与 onRemove 的触发
     * 关系).</p>
     *
     * @param bulletId id of the bullet to remove
     * @param reason   why the bullet is being removed; passed to the
     *                 {@code ON_REMOVE} hook
     */
    public void removeBullet(int bulletId, RemoveReason reason) {
        BulletRecord bullet = bulletsById.remove(bulletId);
        if (bullet == null) {
            return;
        }
        // Detach the spatial listener and evict from the chunk index before
        // firing ON_REMOVE hooks: a hook may mutate the bullet's position, and
        // we must not re-index an already-removed record.
        bullet.setPositionListener(null);
        removeFromSpatialIndex(bullet, chunkKeyOf(bullet.getPosition()));
        BulletHookInvoker.fireOnRemove(bullet, reason);
    }

    /**
     * Evicts every bullet currently tracked by this manager, firing the
     * {@code ON_REMOVE} hook for each with the supplied reason
     * (设计文档 §维度卸载移除).
     *
     * <p>Used primarily on dimension unload (with
     * {@link RemoveReason#DIMENSION_UNLOAD}) to ensure every bullet's
     * {@code ON_REMOVE} cleanup callbacks run before the manager is
     * garbage-collected along with its {@link Level}. Without this explicit
     * eviction the {@code WeakHashMap} would silently reclaim the manager
     * and the {@code ON_REMOVE} hook would never fire for the
     * dimension-unload reason, leaking any resources held by listeners
     * (particle systems, external mod state records, etc.).</p>
     *
     * <p>Iterates over a defensive snapshot from {@link #getAllBullets()}, so
     * removals during iteration are safe (each {@link #removeBullet} call
     * mutates the underlying map, but the snapshot is an independent copy —
     * see iteration-safety notes in {@link BulletTickHandler}).</p>
     *
     * @param reason the reason passed to each bullet's {@code ON_REMOVE} hook
     */
    public void evictAll(RemoveReason reason) {
        Collection<BulletRecord> bullets = getAllBullets();
        for (BulletRecord bullet : bullets) {
            removeBullet(bullet.getBulletId(), reason);
        }
    }

    /**
     * Removes every bullet currently tracked by this manager, firing the
     * {@code ON_REMOVE} hook for each with {@link RemoveReason#MANUAL}
     * (设计文档 §MANUAL 原因 — 管理员命令、清理 API).
     *
     * <p>This is the public batch-cleanup API intended for administrative
     * commands and scripted clear-all operations. It delegates to
     * {@link #evictAll(RemoveReason)} with {@code MANUAL} so that every
     * bullet's {@code ON_REMOVE} cleanup callbacks run before eviction,
     * preventing resource leaks in listeners (S5).</p>
     *
     * <p>This reason is never produced by the normal flight simulation path;
     * it is reserved for explicit API or command-driven removal.</p>
     */
    public void clearAll() {
        evictAll(RemoveReason.MANUAL);
    }

    /**
     * Looks up a bullet by id.
     *
     * @param id the bullet id
     * @return the matching record, or {@code null} if no live bullet has that id
     */
    @Nullable
    public BulletRecord getBulletById(int id) {
        return bulletsById.get(id);
    }

    /**
     * Returns a live, read-only view of all active bullets for iteration.
     *
     * <p>Unlike {@link #getAllBullets()} this does <em>not</em> take a
     * defensive copy — the returned collection is unmodifiable (remove/clear
     * throw {@link UnsupportedOperationException}), and iteration remains
     * weakly consistent across concurrent modification (the underlying
     * {@link ConcurrentHashMap} iterators are weakly consistent): removals
     * during iteration never throw, and entries <em>added</em> during
     * iteration may or may not be observed by the same pass. Intended for
     * read-only passes such as the per-tick sync broadcast, which never
     * removes bullets while iterating.</p>
     *
     * @return an unmodifiable live view of the bullet id index values
     */
    public Collection<BulletRecord> getActiveBullets() {
        return Collections.unmodifiableCollection(bulletsById.values());
    }

    /**
     * Returns a point-in-time unmodifiable snapshot of all active bullets in
     * this dimension (设计文档 §getAllBullets).
     *
     * <p>A defensive copy is taken so callers may iterate safely even while the
     * tick loop mutates the underlying set.</p>
     *
     * @return an unmodifiable collection of every currently active bullet
     */
    public Collection<BulletRecord> getAllBullets() {
        return Collections.unmodifiableList(new ArrayList<>(bulletsById.values()));
    }

    // --- Spatial index (阶段 2 / 任务 2.1) --------------------------------

    /**
     * Returns every active bullet whose current position lies within the given
     * radius of {@code center}, using Chebyshev (chessboard) distance in the
     * horizontal plane — {@code max(|dx|, |dz|)} — matching the square shape of
     * a player's chunk tracking view (the same metric
     * {@link org.yanbwe.modularshoot.network.BulletSyncService} uses to cull
     * per player). The vertical axis is intentionally excluded, matching the
     * sync service's convention.
     *
     * <p>The query scans only the chunk buckets that could intersect the radius
     * (rather than every active bullet), then filters each candidate by the
     * exact Chebyshev distance. The result is a point-in-time, weakly-consistent
     * snapshot safe for the per-tick read-only sync pass.
     *
     * <p>Buckets are addressed by packed chunk long keys
     * ({@link ChunkPos#asLong(int, int)}) computed inline, so this per-tick,
     * per-player hot path allocates no {@link ChunkPos} objects for candidate
     * buckets (阶段 2 / 任务 2.1 optimised range query).
     *
     * @param center the query center, typically the player's position
     * @param radius the cull radius in blocks (Chebyshev, horizontal)
     * @return an unmodifiable collection of bullets within the radius
     */
    public Collection<BulletRecord> getActiveBulletsInRange(Vec3 center, double radius) {
        int centerChunkX = SectionPos.blockToSectionCoord(center.x);
        int centerChunkZ = SectionPos.blockToSectionCoord(center.z);
        // Floor the radius to chunks and widen by one guaranteed margin so the
        // scan always covers the boundary chunk; exact Chebyshev filtering
        // below discards any extra candidates.
        int chunkRadius = (int) Math.floor(radius / 16.0) + 2;
        Set<BulletRecord> result = new LinkedHashSet<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                Set<BulletRecord> bucket = bulletsByChunk.get(
                        ChunkPos.asLong(centerChunkX + dx, centerChunkZ + dz));
                if (bucket == null) {
                    continue;
                }
                for (BulletRecord bullet : bucket) {
                    if (isWithinChebyshevRadius(bullet.getPosition(), center, radius)) {
                        result.add(bullet);
                    }
                }
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    /**
     * Position-change callback attached to each registered {@link BulletRecord}.
     * When a bullet crosses a chunk boundary its bucket is updated so the
     * spatial index stays consistent regardless of which caller moved it.
     *
     * @param bullet the bullet whose position changed
     * @param oldPos the previous position
     * @param newPos the new position
     */
    private void onBulletPositionChanged(BulletRecord bullet, Vec3 oldPos, Vec3 newPos) {
        long oldKey = chunkKeyOf(oldPos);
        long newKey = chunkKeyOf(newPos);
        if (oldKey != newKey) {
            removeFromSpatialIndex(bullet, oldKey);
            addToSpatialIndex(bullet, newKey);
        }
    }

    /** Inserts a bullet into the bucket for the given chunk key. */
    private void addToSpatialIndex(BulletRecord bullet, long chunkKey) {
        bulletsByChunk.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(bullet);
    }

    /** Evicts a bullet from the bucket for the given chunk key, pruning empty buckets. */
    private void removeFromSpatialIndex(BulletRecord bullet, long chunkKey) {
        Set<BulletRecord> bucket = bulletsByChunk.get(chunkKey);
        if (bucket != null) {
            bucket.remove(bullet);
            if (bucket.isEmpty()) {
                bulletsByChunk.remove(chunkKey, bucket);
            }
        }
    }

    /**
     * Packed chunk-coordinate long key for a world position (same conversion
     * as the tick handler): {@link ChunkPos#asLong(int, int)} of the section
     * coordinates of the x/z components. The long is used as the internal
     * bucket key instead of allocating a {@link ChunkPos} per access.
     */
    private static long chunkKeyOf(Vec3 pos) {
        return ChunkPos.asLong(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.z));
    }

    /** Chebyshev horizontal-distance test matching the sync service's culling metric. */
    private static boolean isWithinChebyshevRadius(Vec3 pos, Vec3 center, double radius) {
        double dx = Math.abs(center.x - pos.x);
        double dz = Math.abs(center.z - pos.z);
        return Math.max(dx, dz) <= radius;
    }

    // --- Test support (package-private) ---------------------------------

    /**
     * Package-private read-only view of the per-chunk spatial index (packed
     * chunk key → bullet bucket). Exposed only so the headless unit tests can
     * verify the index invariant "each live bullet appears in exactly one
     * bucket" directly against the internal structure.
     */
    Map<Long, Set<BulletRecord>> getSpatialIndex() {
        return Collections.unmodifiableMap(bulletsByChunk);
    }

}
