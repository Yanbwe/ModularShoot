package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;

/**
 * Continuous collision detection for in-flight bullets
 * (设计文档 §子弹飞行与碰撞检测).
 *
 * <p>For each tick the bullet advances from {@code prevPos} to {@code curPos}.
 * This detector finds the <em>nearest</em> hit along that step — comparing the
 * first block intersection ({@code dBlock}) with the first entity intersection
 * ({@code dEntity}) and returning the closer one. "最近命中" semantics:
 * {@code dEntity < dBlock} → entity hit; {@code dBlock ≤ dEntity} → block hit;
 * neither → no hit. This avoids "same-tick wall-bang plus entity hit" ambiguity
 * (设计文档 §取更近者).</p>
 *
 * <h2>bullet_size handling</h2>
 * <p>The bullet's collision volume is a sphere of radius {@code bullet_size}
 * swept along the step, forming a <em>capsule</em> (设计文档 §胶囊体连续碰撞).
 * This implementation uses two well-known simplifications:</p>
 * <ul>
 *   <li><b>bullet_size = 0</b> — the swept volume degenerates to a line segment
 *       (射线退化). Block detection uses vanilla {@link Level#clip(ClipContext)};
 *       entity detection uses {@link AABB#clip(Vec3, Vec3)} ray-AABB
 *       intersection.</li>
 *   <li><b>bullet_size &gt; 0</b> — entity detection inflates each entity's AABB
 *       by {@code bullet_size} then ray-clips it (the standard "inflated-AABB
 *       raycast" approximation of a swept sphere: a sphere of radius r swept
 *       along a ray ≈ a ray cast against AABBs expanded by r). Block detection
 *       still uses the centre-line {@code level.clip} as a conservative
 *       approximation of capsule-vs-VoxelShape; a full per-block capsule test
 *       is intentionally deferred — the centre ray already catches the first
 *       solid block face along the path, which is the common case.</li>
 * </ul>
 *
 * <h2>Penetration dedup</h2>
 * <p>Entities already in {@link BulletRecord#getPenetratedEntities()} and the
 * shooter are skipped (设计文档 §穿透去重). Blocks already in
 * {@link BulletRecord#getPenetratedBlocks()} are skipped by re-clipping past
 * them, so a slow bullet resting inside a penetrated block does not re-hit it
 * every tick. The dedup sets are <em>written</em> by the penetration layer
 * (subtask 11); this detector only <em>reads</em> them.</p>
 *
 * <p>This is a stateless utility class; all methods are pure with respect to
 * the bullet record (they never mutate it).</p>
 */
public final class CollisionDetector {

    /** Attribute id for {@code bullet_size}, read from the frozen snapshot. */
    private static final ResourceLocation BULLET_SIZE_ID =
            ModularShootAttributes.BULLET_SIZE.getKey().location();

    /** Nudge applied to advance the ray start past a skipped (penetrated) block. */
    private static final double SKIP_NUDGE = 1.0E-4;

    /** Caps the iterative block-skip loop to avoid pathological infinite loops. */
    private static final int MAX_BLOCK_SKIPS = 64;

    /** Margin added around the path AABB when querying nearby entities. */
    private static final double ENTITY_SEARCH_MARGIN = 2.0;

    /**
     * Per-tick per-chunk entity query result: the query box used for the
     * {@link Level#getEntitiesOfClass} call and the candidate entities it
     * returned. Candidates are reused by every bullet in the same chunk whose
     * search box is contained in {@link #box()} (设计文档 §空间分区).
     *
     * <p>Package-visible: {@link BulletTickHandler} holds the per-tick cache
     * map of this type (设计文档 §空间分区).</p>
     */
    record ChunkEntityQuery(AABB box, List<LivingEntity> entities) {
    }

    private CollisionDetector() {
    }

    /**
     * Detects the nearest collision along the bullet's {@code [prevPos, curPos]}
     * step, comparing block and entity hits (设计文档 §最近命中语义).
     *
     * @param level   the server level to query blocks/entities in
     * @param bullet  the bullet record (supplies snapshot, shooter, dedup sets)
     * @param prevPos the bullet's position before this tick's advance
     * @param curPos  the bullet's position after this tick's advance
     * @return the nearest hit, or {@link CollisionResult#none()} if nothing was hit
     */
    public static CollisionResult detectCollision(Level level, BulletRecord bullet, Vec3 prevPos, Vec3 curPos) {
        return detectCollision(level, bullet, prevPos, curPos, new HashMap<>());
    }

    /**
     * Like the 4-param overload, but entity query results are cached per chunk
     * and reused across bullets. The cache lives for one tick (held and passed
     * in by {@code BulletTickHandler}); passing an empty map behaves exactly
     * like the 4-param overload.
     *
     * @param level       the server level to query blocks/entities in
     * @param bullet      the bullet record (supplies snapshot, shooter, dedup sets)
     * @param prevPos     the bullet's position before this tick's advance
     * @param curPos      the bullet's position after this tick's advance
     * @param entityCache the per-tick per-chunk entity candidate cache
     * @return the nearest hit, or {@link CollisionResult#none()} if nothing was hit
     */
    static CollisionResult detectCollision(
            Level level, BulletRecord bullet, Vec3 prevPos, Vec3 curPos,
            Map<Long, ChunkEntityQuery> entityCache) {
        double bulletSize = bullet.getSnapshot().getStat(BULLET_SIZE_ID);
        CollisionResult blockHit = detectBlockCollision(level, bullet, prevPos, curPos);
        CollisionResult entityHit = detectEntityCollision(level, bullet, prevPos, curPos, bulletSize, entityCache);
        return nearestHit(blockHit, entityHit);
    }

    /**
     * Picks the nearer of a block and an entity hit per the design's tie-break:
     * entity wins only when strictly closer ({@code dEntity < dBlock}); a tie
     * or a block-only hit favours the block; both-empty yields no hit.
     *
     * @param blockHit  the block collision result (may be NONE)
     * @param entityHit the entity collision result (may be NONE)
     * @return the nearer result, or {@link CollisionResult#none()} if both miss
     */
    private static CollisionResult nearestHit(CollisionResult blockHit, CollisionResult entityHit) {
        boolean noBlock = blockHit.hitType() == CollisionResult.HitType.NONE;
        boolean noEntity = entityHit.hitType() == CollisionResult.HitType.NONE;
        if (noBlock && noEntity) {
            return CollisionResult.none();
        }
        if (!noEntity && (noBlock || entityHit.distance() < blockHit.distance())) {
            return entityHit;
        }
        return blockHit;
    }

    /**
     * Detects the nearest non-penetrated block hit along the step using vanilla
     * {@link Level#clip(ClipContext)} (设计文档 §方块 raycast).
     *
     * <p>Both {@code bullet_size = 0} and {@code bullet_size > 0} use the
     * centre-line clip; the radius-based block expansion is a documented
     * simplification (see class Javadoc).</p>
     *
     * @param level   the server level
     * @param bullet  the bullet record (supplies the penetrated-blocks set)
     * @param prevPos step start
     * @param curPos  step end
     * @return a block-hit result, or {@link CollisionResult#none()} if no block is hit
     */
    private static CollisionResult detectBlockCollision(Level level, BulletRecord bullet, Vec3 prevPos, Vec3 curPos) {
        BlockHitResult blockHit = clipSkippingPenetrated(level, prevPos, curPos, bullet.getPenetratedBlocks());
        if (blockHit.getType() != HitResult.Type.BLOCK) {
            return CollisionResult.none();
        }
        double distance = prevPos.distanceTo(blockHit.getLocation());
        return CollisionResult.block(blockHit.getBlockPos(), blockHit.getDirection(), distance);
    }

    /**
     * Raycasts {@code [from, to]} against world colliders, skipping any block
     * whose position is in {@code penetrated} (设计文档 §穿透去重 — 方块).
     *
     * <p>When the nearest hit is a penetrated block, the ray start is nudged
     * just past the hit point and the clip repeats, until a non-penetrated
     * block is found, the ray misses, or {@link #MAX_BLOCK_SKIPS} is reached.
     * This keeps a slow bullet inside an already-penetrated block from
     * re-triggering a hit every tick.</p>
     *
     * <p>When the penetrated set is empty (the common case) a fast path performs a single clip with no step-vector computation.</p>
     *
     * @param level      the server level
     * @param from       ray start
     * @param to         ray end
     * @param penetrated block positions to skip
     * @return the nearest non-penetrated block hit, or a MISS result
     */
    private static BlockHitResult clipSkippingPenetrated(Level level, Vec3 from, Vec3 to, Set<BlockPos> penetrated) {
        // Fast path: no penetrated blocks (the common case) — a single clip over
        // the step with none of the skip-loop bookkeeping (no step-vector work).
        if (penetrated.isEmpty()) {
            return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        }
        Vec3 step = to.subtract(from);
        double stepLen = step.length();
        Vec3 stepDir = stepLen < 1.0E-12 ? new Vec3(0.0, 0.0, 0.0) : step.scale(1.0 / stepLen);
        Vec3 start = from;
        for (int i = 0; i < MAX_BLOCK_SKIPS; i++) {
            BlockHitResult hit = level.clip(new ClipContext(start, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.BLOCK || !penetrated.contains(hit.getBlockPos())) {
                return hit;
            }
            Vec3 next = hit.getLocation().add(stepDir.scale(SKIP_NUDGE));
            if (next.distanceToSqr(to) <= SKIP_NUDGE * SKIP_NUDGE) {
                return BlockHitResult.miss(to, hit.getDirection(), hit.getBlockPos());
            }
            start = next;
        }
        return BlockHitResult.miss(to, Direction.UP, BlockPos.ZERO);
    }

    private static CollisionResult detectEntityCollision(
            Level level, BulletRecord bullet, Vec3 prevPos, Vec3 curPos, double bulletSize,
            Map<Long, ChunkEntityQuery> entityCache) {
        AABB searchAABB = buildSearchAABB(prevPos, curPos, bulletSize);
        List<LivingEntity> entities = queryEntityCandidates(level, bullet, searchAABB, bulletSize, entityCache);
        return findNearestEntity(entities, bullet, prevPos, curPos, bulletSize, searchAABB);
    }

    /**
     * Returns the entity candidates for this bullet's step, reusing the
     * per-chunk query result when the bullet's search box fits inside the
     * cached query box; otherwise queries the union of the chunk column box
     * and the step's own search box (covering the full step, including
     * steps that cross the chunk boundary).
     *
     * <p>Correctness: {@link Level#getEntitiesOfClass} returns every entity
     * whose bounding box intersects the query box. When {@code searchAABB} is
     * contained in the cached box, every entity that could intersect the
     * bullet's step is therefore already in the cached candidate list — reuse
     * is exact, not approximate. The union fallback likewise covers bullets
     * whose {@code bullet_size} or step length extends beyond the chunk
     * column box: the widened box contains {@code searchAABB}, so its
     * candidate list covers the whole step, exactly as the pre-cache
     * per-bullet query did. The widened box is cached, so later bullets in
     * the same chunk are more likely to be contained in it and hit the cache.</p>
     *
     * <p>Freshness: the candidate list is frozen at the tick's first query
     * and reused by every bullet in the chunk. During
     * {@code LevelTickEvent.Pre} entities have not ticked yet, so the frozen
     * list matches a fresh per-bullet query (entities that died meanwhile are
     * filtered by {@link #isSkippableEntity}). A theoretical divergence
     * remains only if a trait callback teleports or summons an entity into
     * the bullet's path within the same tick.</p>
     *
     * @param level       the server level
     * @param bullet      the bullet record (position decides the chunk key)
     * @param searchAABB  the bullet's step search box
     * @param bulletSize  the bullet radius (inflates the chunk query box)
     * @param entityCache the per-tick per-chunk cache to read/update
     * @return candidate {@link LivingEntity} instances near the step
     */
    private static List<LivingEntity> queryEntityCandidates(
            Level level, BulletRecord bullet, AABB searchAABB, double bulletSize,
            Map<Long, ChunkEntityQuery> entityCache) {
        Vec3 pos = bullet.getPosition();
        long chunkKey = ChunkPos.asLong(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.z));
        ChunkEntityQuery cached = entityCache.get(chunkKey);
        // This version's AABB lacks the contains(AABB) overload; inline the
        // equivalent inclusive containment test (min/max bounds, boundary equal).
        if (cached != null
                && cached.box().minX <= searchAABB.minX && cached.box().maxX >= searchAABB.maxX
                && cached.box().minY <= searchAABB.minY && cached.box().maxY >= searchAABB.maxY
                && cached.box().minZ <= searchAABB.minZ && cached.box().maxZ >= searchAABB.maxZ) {
            return cached.entities();
        }
        // Fallback: the bullet's step is not fully covered by the per-chunk
        // column box (e.g. a step crossing the chunk boundary extends into
        // the source chunk beyond the pad). Query the union of the column
        // box and the step's own search box so the whole step is covered —
        // identical coverage to the pre-cache per-bullet query — and cache
        // the widened box so later bullets in the same chunk hit the cache.
        AABB queryBox = chunkQueryBox(level, chunkKey, bulletSize).minmax(searchAABB);
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, queryBox);
        entityCache.put(chunkKey, new ChunkEntityQuery(queryBox, entities));
        return entities;
    }

    /**
     * Builds the per-chunk entity query box: the chunk column at full world
     * height, inflated by {@link #ENTITY_SEARCH_MARGIN} plus the bullet radius.
     */
    private static AABB chunkQueryBox(Level level, long chunkKey, double bulletSize) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        double pad = ENTITY_SEARCH_MARGIN + bulletSize;
        // getMaxBuildHeight() is the exclusive top (getMinBuildHeight() + height),
        // i.e. the design's (getMaxY() + 1.0) bound; the -1.0/+0 slack keeps the
        // box a superset of the full world column.
        return new AABB(
                chunkX * 16.0 - pad, level.getMinBuildHeight() - 1.0, chunkZ * 16.0 - pad,
                chunkX * 16.0 + 16.0 + pad, level.getMaxBuildHeight(), chunkZ * 16.0 + 16.0 + pad);
    }

    /**
     * Builds the AABB used to query nearby entities: the bounding box of the
     * step {@code [prevPos, curPos]} inflated by {@code bulletSize} plus a
     * fixed margin that covers typical entity hitbox extents.
     *
     * @param prevPos    step start
     * @param curPos     step end
     * @param bulletSize the bullet radius
     * @return an AABB enclosing all entities that could possibly be hit
     */
    private static AABB buildSearchAABB(Vec3 prevPos, Vec3 curPos, double bulletSize) {
        double pad = bulletSize + ENTITY_SEARCH_MARGIN;
        return new AABB(
                Math.min(prevPos.x, curPos.x) - pad,
                Math.min(prevPos.y, curPos.y) - pad,
                Math.min(prevPos.z, curPos.z) - pad,
                Math.max(prevPos.x, curPos.x) + pad,
                Math.max(prevPos.y, curPos.y) + pad,
                Math.max(prevPos.z, curPos.z) + pad);
    }

    /**
     * Iterates candidate entities and returns the nearest ray-hit on an
     * (inflated) hitbox, skipping already-penetrated entities and the shooter
     * (设计文档 §穿透去重 — 实体).
     *
     * @param entities    candidate {@link LivingEntity} instances near the path
     * @param bullet      the bullet record (supplies shooter + dedup set)
     * @param prevPos     step start
     * @param curPos      step end
     * @param bulletSize  the bullet radius (inflates each hitbox before clipping)
     * @param searchAABB  the bullet's step search box (prefilter for chunk-wide candidate lists)
     * @return the nearest entity hit, or {@link CollisionResult#none()}
     */
    private static CollisionResult findNearestEntity(
            List<LivingEntity> entities, BulletRecord bullet, Vec3 prevPos, Vec3 curPos,
            double bulletSize, AABB searchAABB) {
        CollisionResult nearest = CollisionResult.none();
        for (LivingEntity entity : entities) {
            if (isSkippableEntity(entity, bullet)) {
                continue;
            }
            // Cheap prefilter: chunk-wide candidate lists include entities far from
            // this bullet's step; skip them before allocating the inflated hitbox.
            // searchAABB is already inflated by (bulletSize + margin), so an entity
            // whose uninflated box does not intersect it can never be hit.
            if (!searchAABB.intersects(entity.getBoundingBox())) {
                continue;
            }
            Optional<Vec3> hitOpt = entity.getBoundingBox().inflate(bulletSize).clip(prevPos, curPos);
            if (hitOpt.isEmpty()) {
                continue;
            }
            double distance = prevPos.distanceTo(hitOpt.get());
            if (distance < nearest.distance()) {
                nearest = CollisionResult.entity(entity, distance);
            }
        }
        return nearest;
    }

    /**
     * Determines whether an entity should be skipped during entity collision
     * detection (设计文档 §穿透去重).
     *
     * <p>An entity is skipped when it is dead, already in the bullet's
     * penetrated-entities set, or is the shooter (null-safe comparison).</p>
     *
     * @param entity the candidate entity
     * @param bullet the bullet record
     * @return {@code true} if the entity must not be considered for a hit
     */
    private static boolean isSkippableEntity(Entity entity, BulletRecord bullet) {
        if (!entity.isAlive()) {
            return true;
        }
        if (bullet.getPenetratedEntities().contains(entity.getUUID())) {
            return true;
        }
        UUID shooter = bullet.getShooter();
        return shooter != null && shooter.equals(entity.getUUID());
    }
}
