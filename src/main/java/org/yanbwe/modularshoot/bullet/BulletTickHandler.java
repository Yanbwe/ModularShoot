package org.yanbwe.modularshoot.bullet;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.damage.DamageApplier;
import org.yanbwe.modularshoot.damage.DamageHandlerRegistry;
import org.yanbwe.modularshoot.network.BulletHitBroadcastService;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket;
import org.yanbwe.modularshoot.trait.RemoveReason;

/**
 * Server-side per-tick bullet flight driver (设计文档 §子弹飞行与碰撞检测).
 *
 * <p>Listens to {@link LevelTickEvent.Pre} on the game bus and, for every
 * active bullet in the dimension, performs the full flight pipeline
 * (设计文档 §执行顺序):
 * {@code onTick} hook, aging, straight-line position advance, collision
 * detection (with per-chunk entity candidate cache), penetration
 * bookkeeping, damage application, range expiry, then unloaded-chunk
 * expiry.</p>
 *
 * <p><b>Execution order rationale.</b> Collision detection runs <em>before</em>
 * range and unloaded-chunk expiry so that a bullet whose final step both
 * passes through a target and exceeds its range still hits the target. If
 * range/unloaded checks ran first, the bullet would be removed for expiring
 * and the target along the last step would be silently missed
 * (设计文档 §执行顺序 — 碰撞检测先于范围/未加载检查).</p>
 *
 * <p><b>Server-only.</b> The event fires on both logical sides; a
 * {@code level.isClientSide()} guard ensures the authoritative server drives
 * bullet simulation, matching the NeoForge-recommended pattern for
 * {@code LevelTickEvent} handlers.</p>
 *
 * <h2>Iteration safety</h2>
 * <p>{@link BulletManager#getAllBullets()} returns a defensive unmodifiable
 * snapshot, so the tick loop may call {@code removeBullet} mid-iteration
 * without {@code ConcurrentModificationException}. After a removal the bullet
 * is skipped for the remainder of this tick via an early return from
 * {@link #processBullet}.</p>
 *
 * <h2>Position advance</h2>
 * <p>Each tick the bullet advances {@code bullet_speed / 20} blocks along its
 * current (normalized) direction — no gravity, straight-line flight
 * (设计文档 §位置推进). The step length equals {@code bullet_speed / 20}
 * (the attribute value is in blocks-per-second; dividing by 20 yields
 * blocks-per-tick), read from the frozen snapshot, so in-flight attribute
 * changes on the gun do not affect already-fired bullets. A bullet whose
 * frozen {@code bullet_speed} is ≤ 0 (the attribute floor is 0 and plugin
 * modifiers can push it there) can never advance — its step length and
 * traveled distance stay zero, so range expiry can never fire; it is removed
 * immediately as {@code EXPIRED} before position advance (审查修复 M2).</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletTickHandler {

    /** Attribute id for {@code bullet_speed}, read from the snapshot each tick. */
    private static final ResourceLocation BULLET_SPEED_ID =
            ModularShootAttributes.BULLET_SPEED.getKey().location();

    /** Attribute id for {@code range}, read from the snapshot each tick. */
    private static final ResourceLocation RANGE_ID =
            ModularShootAttributes.RANGE.getKey().location();

    /** Attribute id for {@code hit_damage}, read from the snapshot on entity hit. */
    private static final ResourceLocation HIT_DAMAGE_ID =
            ModularShootAttributes.HIT_DAMAGE.getKey().location();

    private BulletTickHandler() {
    }

    /**
     * Fired once per tick per level on both logical sides; guarded to process
     * only the authoritative server side.
     *
     * @param event the pre-level-tick event carrying the ticking level
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        tickBullets(level);
    }

    /**
     * Iterates every active bullet in the dimension and advances its flight
     * by one tick (设计文档 §子弹飞行与碰撞检测).
     *
     * <p>The collection returned by {@link BulletManager#getAllBullets()} is a
     * defensive snapshot, so removals during iteration are safe.</p>
     *
     * @param level the server level whose bullets are being ticked
     */
    private static void tickBullets(Level level) {
        BulletManager manager = BulletManager.get(level);
        // 审查优化 P8: live 视图替代每 tick 防御拷贝（getAllBullets 的
        // new ArrayList）。ConcurrentHashMap 迭代弱一致，迭代中 removeBullet
        // 不会抛 CME；被移除的子弹若仍被迭代到，processBullet 的 liveness
        // 复查（onTick 后 getBulletById == null）会跳过它——与原快照行为一致。
        Collection<BulletRecord> bullets = manager.getActiveBullets();
        // Per-tick entity candidate cache: one chunk query shared by every
        // bullet in the same chunk (设计文档 §空间分区). Discarded with this
        // method's frame; the server tick is single-threaded.
        Map<Long, CollisionDetector.ChunkEntityQuery> entityCache = new HashMap<>();
        for (BulletRecord bullet : bullets) {
            processBullet(level, manager, bullet, entityCache);
        }
    }

    /**
     * Processes a single bullet for one tick, executing the flight pipeline
     * (设计文档 §执行顺序):
     * {@code onTick} hook, aging, position advance, collision detection
     * (with per-chunk entity candidate cache), penetration/damage, range
     * expiry, then unloaded-chunk expiry.
     *
     * <p>Collision detection runs <em>before</em> range and unloaded-chunk
     * expiry. This guarantees that when a bullet's step both crosses a target
     * and exceeds its range (or lands in an unloaded chunk), the target is
     * hit first rather than being skipped because the bullet was removed for
     * expiring. Each stage returns early once the bullet is removed so no
     * further processing runs on a dead record.</p>
     *
     * <p><b>onTick liveness check (W13 fix).</b> Immediately after the
     * {@code onTick} hook fires, the bullet's continued presence in the
     * manager's id index is verified. If an {@code onTick} callback removed
     * the bullet (e.g. a fuse/detonation trait), the remaining stages are
     * skipped — no aging, position advance or collision detection runs on
     * the already-removed record.</p>
     *
     * @param level   the server level
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet to advance
     * @param entityCache the per-tick per-chunk entity candidate cache
     *                    (设计文档 §空间分区)
     */
    private static void processBullet(Level level, BulletManager manager, BulletRecord bullet,
                                      Map<Long, CollisionDetector.ChunkEntityQuery> entityCache) {
        // 1. onTick trait hook — fire ON_TICK callbacks before aging/advance.
        BulletHookInvoker.fireOnTick(bullet);

        // 1b. liveness check (W13 fix): an onTick hook may have called
        //     manager.removeBullet(...) (e.g. a "fuse" trait that detonates
        //     after N ticks). Once the bullet is evicted from the id index,
        //     every subsequent stage would operate on a stale record —
        //     advancing a dead bullet's position, even re-detecting
        //     collisions for a projectile that no longer exists. Bail out
        //     early so the rest of the pipeline is
        //     skipped for a bullet removed by its own onTick hook.
        if (manager.getBulletById(bullet.getBulletId()) == null) {
            return;
        }

        // 2. age++
        bullet.incrementAge();

        // 2.5 stationary expiry — speed ≤ 0 can never advance past range
        //     (审查修复 M2): mirrors checkRangeExpiry's onExpire → remove(EXPIRED)
        //     ordering, then bails out of the rest of this tick's pipeline.
        if (isStationary(bullet.getSnapshot())) {
            BulletHookInvoker.fireOnExpire(bullet);
            manager.removeBullet(bullet.getBulletId(), RemoveReason.EXPIRED);
            return;
        }

        // 3. position advance (records prevPos for collision detection)
        Vec3 prevPos = bullet.getPosition();
        advancePosition(bullet);

        // 4. collision detection → penetration dedup → damage application
        //    (设计文档 §执行顺序: 碰撞检测必须在范围/未加载检查之前执行，
        //    否则子弹本 tick 步进既穿过目标又超出射程时会因射程移除而漏判命中)
        if (handleCollision(level, manager, bullet, prevPos, entityCache)) {
            return;
        }

        // 5. range expiry — the bullet's lifetime has ended
        //    (设计文档 §执行顺序: 范围检查在碰撞检测之后)
        if (checkRangeExpiry(manager, bullet)) {
            return;
        }

        // 6. unloaded-chunk expiry — cannot raycast into unloaded space
        //    (设计文档 §执行顺序: 未加载区块处理在范围检查之后)
        if (checkUnloadedChunk(level, manager, bullet)) {
            return;
        }
    }

    /**
     * Runs collision detection on the bullet's step and dispatches the result
     * to the penetration/damage pipeline (设计文档 §步骤十).
     *
     * <p>When the nearest hit is an entity, a {@link BulletHitS2CPacket} is
     * broadcast to nearby clients, the damage post-processor chain computes
     * the final damage, {@link DamageApplier#applyDamage} applies it via the
     * vanilla {@code hurt()} flow, the {@code onHit} trait hook fires for
     * side effects, and then {@link PenetrationHandler#handleEntityHit}
     * decides whether the bullet penetrates or is removed. When the nearest
     * hit is a block, the hit is broadcast and
     * {@link PenetrationHandler#handleBlockHit} fires the {@code onBlockHit}
     * hook and decides penetration vs removal.</p>
     *
     * <p>Penetration dedup is handled inside {@link PenetrationHandler} (and
     * the dedup sets are read by {@link CollisionDetector}): a target already
     * in the bullet's dedup set is skipped, so the same entity/block is never
     * hit twice by one bullet (设计文档 §穿透去重).</p>
     *
     * @param level   the server level
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet being processed
     * @param prevPos the bullet's position before this tick's advance
     * @param entityCache the per-tick per-chunk entity candidate cache
     *                    (设计文档 §空间分区)
     * @return {@code true} if the bullet was removed by this collision (the
     *         caller should skip further processing this tick); {@code false}
     *         if the bullet continues flying (no hit, or penetration succeeded)
     */
    private static boolean handleCollision(Level level, BulletManager manager, BulletRecord bullet, Vec3 prevPos,
                                           Map<Long, CollisionDetector.ChunkEntityQuery> entityCache) {
        Vec3 curPos = bullet.getPosition();
        CollisionResult collision = CollisionDetector.detectCollision(level, bullet, prevPos, curPos, entityCache);
        return switch (collision.hitType()) {
            case ENTITY -> {
                Vec3 hitPos = computeHitPos(bullet, prevPos, collision.distance());
                yield handleEntityCollision((ServerLevel) level, manager, bullet, collision.hitEntity(), hitPos);
            }
            case BLOCK -> {
                Vec3 hitPos = computeHitPos(bullet, prevPos, collision.distance());
                yield handleBlockCollision((ServerLevel) level, manager, bullet, collision.blockPos(), collision.blockFace(), hitPos);
            }
            case NONE -> false; // no hit this tick — bullet continues flying
        };
    }

    /**
     * Processes an entity collision: broadcasts the hit to nearby clients,
     * runs the damage post-processor chain, applies the finalised damage,
     * fires the {@code onHit} hook, then delegates to
     * {@link PenetrationHandler} for penetration bookkeeping
     * (设计文档 §步骤十 — 命中实体).
     *
     * @param level   the server level (used for hit-broadcast distribution)
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet that hit the entity
     * @param target  the entity that was hit; never {@code null}
     * @param hitPos  the exact world-space hit position for client effects
     * @return {@code true} if the bullet was removed (penetration exhausted);
     *         {@code false} if the bullet continues flying (successful penetration)
     */
    private static boolean handleEntityCollision(ServerLevel level, BulletManager manager, BulletRecord bullet, Entity target, Vec3 hitPos) {
        BulletHitBroadcastService.broadcastHit(level, bullet, hitPos, BulletHitS2CPacket.HitType.ENTITY, target.getId());
        double baseDamage = bullet.getSnapshot().getStat(HIT_DAMAGE_ID);
        double finalDamage = DamageHandlerRegistry.processChain(bullet, target, baseDamage);
        DamageApplier.applyDamage(bullet, target, finalDamage);
        BulletHookInvoker.fireOnHit(bullet, target);
        return PenetrationHandler.handleEntityHit(bullet, target, manager);
    }

    /**
     * Processes a block collision: broadcasts the hit to nearby clients, then
     * delegates to {@link PenetrationHandler} which fires the
     * {@code onBlockHit} hook and decides penetration vs removal
     * (设计文档 §步骤十 — 命中方块).
     *
     * @param level   the server level (used for hit-broadcast distribution)
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet that hit the block
     * @param pos     the hit block position; never {@code null}
     * @param face    the hit block face; never {@code null}
     * @param hitPos  the exact world-space hit position for client effects
     * @return {@code true} if the bullet was removed (penetration exhausted);
     *         {@code false} if the bullet continues flying (successful penetration)
     */
    private static boolean handleBlockCollision(ServerLevel level, BulletManager manager, BulletRecord bullet,
                                                 net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face, Vec3 hitPos) {
        BulletHitBroadcastService.broadcastHit(level, bullet, hitPos, BulletHitS2CPacket.HitType.BLOCK, BulletHitS2CPacket.NO_ENTITY);
        return PenetrationHandler.handleBlockHit(bullet, pos, face, manager);
    }

    /**
     * Computes the exact world-space hit point from the bullet's previous
     * position and the collision distance along its (normalized) direction.
     *
     * <p>Since {@link BulletRecord#getDirection()} is guaranteed normalized
     * at construction, multiplying by {@code distance} yields a displacement
     * of exactly {@code distance} blocks along the flight ray — landing on
     * the precise impact point rather than the tick's end position.</p>
     *
     * @param bullet   the bullet that hit
     * @param prevPos  the bullet's position before this tick's advance
     * @param distance distance from {@code prevPos} to the hit point
     * @return the exact hit position
     */
    private static Vec3 computeHitPos(BulletRecord bullet, Vec3 prevPos, double distance) {
        Vec3 direction = bullet.getDirection();
        return prevPos.add(direction.multiply(distance, distance, distance));
    }

    /**
     * Pure decision: a bullet whose frozen speed is ≤ 0 can never advance
     * past its range and must expire immediately (审查修复 M2).
     *
     * <p>With {@code stepLength = bullet_speed / 20}, a zero or negative
     * speed yields a zero (or backwards, non-accumulating) step and
     * {@code traveledDistance} can never exceed {@code range}, so
     * {@link #checkRangeExpiry} would never fire and the bullet would linger
     * in the {@link BulletManager} forever, burning per-tick hook/broadcast
     * work until dimension unload. A missing stat key degrades to
     * {@code 0.0} via {@link BulletSnapshot#getStat}, i.e. also stationary —
     * consistent with the value {@link #advancePosition} would read.</p>
     *
     * @param snapshot the bullet's frozen snapshot
     * @return {@code true} if the bullet cannot advance and must expire
     */
    static boolean isStationary(BulletSnapshot snapshot) {
        return snapshot.getStat(BULLET_SPEED_ID) <= 0.0;
    }

    /**
     * Advances the bullet's position by {@code bullet_speed / 20} blocks along its
     * current direction and accumulates the traveled distance.
     *
     * <p>No gravity is applied — flight is straight-line. The direction is
     * assumed normalized (guaranteed at construction). The attribute value is
     * in blocks-per-second; dividing by 20 yields blocks-per-tick
     * (设计文档 §位置推进).</p>
     *
     * @param bullet the bullet to advance
     */
    private static void advancePosition(BulletRecord bullet) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        double stepLength = snapshot.getStat(BULLET_SPEED_ID) / 20.0;
        Vec3 direction = bullet.getDirection();
        Vec3 oldPos = bullet.getPosition();
        Vec3 newPos = oldPos.add(direction.multiply(stepLength, stepLength, stepLength));
        bullet.setPosition(newPos);
        bullet.addTraveledDistance((float) stepLength);
    }

    /**
     * Checks whether the bullet has exceeded its {@code range}; if so, fires
     * {@code onExpire} and removes the bullet with
     * {@link RemoveReason#EXPIRED} (设计文档 §范围检查).
     *
     * <p>The {@code ON_EXPIRE} hook fires first (semantic: the bullet's life
     * has ended), then {@link BulletManager#removeBullet} internally fires
     * {@code ON_REMOVE} with {@link RemoveReason#EXPIRED} (设计文档
     * §onExpire 与 onRemove 的触发关系).</p>
     *
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet to check
     * @return {@code true} if the bullet was removed (caller should skip
     *         further processing for this bullet this tick)
     */
    private static boolean checkRangeExpiry(BulletManager manager, BulletRecord bullet) {
        double range = bullet.getSnapshot().getStat(RANGE_ID);
        if (bullet.getTraveledDistance() > range) {
            BulletHookInvoker.fireOnExpire(bullet);
            manager.removeBullet(bullet.getBulletId(), RemoveReason.EXPIRED);
            return true;
        }
        return false;
    }

    /**
     * Checks whether the bullet has advanced into an unloaded chunk; if so,
     * removes the bullet with {@link RemoveReason#UNLOADED_CHUNK}
     * (设计文档 §未加载区块处理).
     *
     * <p>The framework never force-loads chunks for bullet simulation: a
     * bullet entering unloaded space is silently dropped to avoid remote
     * shots triggering large-scale chunk loading. This scenario fires only
     * {@code ON_REMOVE} (via {@link BulletManager#removeBullet}) — it does
     * <em>not</em> fire {@code ON_EXPIRE}, which is reserved for the
     * range-exceeded lifetime-end case (设计文档 §onExpire 与 onRemove 的
     * 触发关系).</p>
     *
     * @param level   the server level used for the chunk-loaded query
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet to check
     * @return {@code true} if the bullet was removed (caller should skip
     *         further processing for this bullet this tick)
     */
    private static boolean checkUnloadedChunk(Level level, BulletManager manager, BulletRecord bullet) {
        Vec3 pos = bullet.getPosition();
        int chunkX = SectionPos.blockToSectionCoord(pos.x);
        int chunkZ = SectionPos.blockToSectionCoord(pos.z);
        if (!level.hasChunk(chunkX, chunkZ)) {
            manager.removeBullet(bullet.getBulletId(), RemoveReason.UNLOADED_CHUNK);
            return true;
        }
        return false;
    }
}
