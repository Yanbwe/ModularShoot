package org.yanbwe.modularshoot.bullet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.render.BulletRenderManager;
import org.yanbwe.modularshoot.network.BulletSyncService;
import org.yanbwe.modularshoot.trait.RemoveReason;

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
 * <h2>Chunk bucketing</h2>
 * <p>Bullets are indexed by their current {@link ChunkPos} so that per-tick
 * collision detection only scans the bullet's chunk and its neighbours (3×3
 * range) instead of every world entity (设计文档 §空间分区). The index is a
 * {@code ConcurrentHashMap<ChunkPos, Set<BulletRecord>>}; each bucket is a
 * concurrent set and is created/removed atomically via
 * {@link ConcurrentHashMap#computeIfAbsent} / {@link ConcurrentHashMap#compute}
 * to avoid race conditions on bucket lifecycle.</p>
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

    /** Chunk bucketing index: chunk → live set of bullets currently in that chunk. */
    private final Map<ChunkPos, Set<BulletRecord>> chunkBuckets = new ConcurrentHashMap<>();

    /** Monotonic per-dimension id counter; starts at 1, guarded by synchronization in {@link #nextBulletId}. */
    private int nextId = 1;

    /** Dimension key retained for logging only (never holds a {@link Level} reference). */
    private final ResourceKey<Level> dimensionKey;

    private BulletManager(ResourceKey<Level> dimensionKey) {
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
     * Registers a bullet with this dimension's manager. The bullet is added to
     * the id index and to the chunk bucket for its current position
     * (设计文档 §空间分区).
     *
     * <p>The bullet must already carry a unique id assigned via
     * {@link #nextBulletId()} (or equivalent). Its position determines the
     * bucket it lands in.</p>
     *
     * @param bullet the bullet record to register
     */
    public void addBullet(BulletRecord bullet) {
        bulletsById.put(bullet.getBulletId(), bullet);
        ChunkPos chunk = toChunkPos(bullet.getPosition());
        chunkBuckets.computeIfAbsent(chunk, key -> ConcurrentHashMap.newKeySet()).add(bullet);
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
        BulletRecord bullet = new BulletRecord(snapshot, shooter, position, direction, bulletId);
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
     * removal), then the {@code ON_REMOVE} callbacks run, and finally the
     * chunk-bucket entry is cleaned up. The {@code ON_EXPIRE} hook — when
     * applicable — is fired by the caller before invoking this method
     * (设计文档 §onExpire 与 onRemove 的触发关系).</p>
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
        BulletHookInvoker.fireOnRemove(bullet, reason);
        removeFromChunkBucket(bullet);
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
     * Atomically removes a bullet from its current chunk bucket, dropping the
     * bucket entry entirely once it becomes empty.
     */
    private void removeFromChunkBucket(BulletRecord bullet) {
        ChunkPos chunk = toChunkPos(bullet.getPosition());
        chunkBuckets.compute(chunk, (key, bucket) -> {
            if (bucket == null) {
                return null;
            }
            bucket.remove(bullet);
            return bucket.isEmpty() ? null : bucket;
        });
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

    /**
     * Returns all bullets located in the chunk bucket of {@code center} plus
     * every chunk within {@code radius} (inclusive) on both axes. With
     * {@code radius = 1} this is the 3×3 neighbourhood used for per-tick
     * collision scanning (设计文档 §空间分区).
     *
     * <p>The returned collection is an unmodifiable snapshot.</p>
     *
     * @param center the central chunk
     * @param radius chunk radius around the centre (0 = same chunk only)
     * @return an unmodifiable collection of bullets in the range
     */
    public Collection<BulletRecord> getBulletsInChunkRange(ChunkPos center, int radius) {
        List<BulletRecord> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                Set<BulletRecord> bucket = chunkBuckets.get(chunk);
                if (bucket != null) {
                    result.addAll(bucket);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Migrates a bullet from its old chunk bucket to a new one when it crosses
     * a chunk boundary (设计文档 §空间分区). Does nothing if the old and new
     * chunks are identical.
     *
     * <p>The caller is responsible for having already updated the bullet's
     * position; this method only maintains the bucket index.</p>
     *
     * @param bullet   the bullet that moved
     * @param oldChunk the chunk the bullet was in before moving
     * @param newChunk the chunk the bullet is now in
     */
    public void updateChunkBucket(BulletRecord bullet, ChunkPos oldChunk, ChunkPos newChunk) {
        if (oldChunk.equals(newChunk)) {
            return;
        }
        chunkBuckets.compute(oldChunk, (key, bucket) -> {
            if (bucket != null) {
                bucket.remove(bullet);
                if (bucket.isEmpty()) {
                    return null;
                }
            }
            return bucket;
        });
        chunkBuckets.computeIfAbsent(newChunk, key -> ConcurrentHashMap.newKeySet()).add(bullet);
    }

    /**
     * Converts a world-space position to its containing chunk coordinates.
     * Uses {@link SectionPos#blockToSectionCoord(double)} which floors the
     * coordinate before shifting, handling negative positions correctly.
     */
    private static ChunkPos toChunkPos(Vec3 pos) {
        return new ChunkPos(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.z));
    }
}
