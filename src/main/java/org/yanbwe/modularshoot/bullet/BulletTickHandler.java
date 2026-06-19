package org.yanbwe.modularshoot.bullet;

import java.util.Collection;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.trait.RemoveReason;

/**
 * Server-side per-tick bullet flight driver (设计文档 §子弹飞行与碰撞检测).
 *
 * <p>Listens to {@link LevelTickEvent.Pre} on the game bus and, for every
 * active bullet in the dimension, performs the flight pipeline: {@code onTick}
 * hook, aging, straight-line position advance, chunk-bucket migration, range
 * expiry, unloaded-chunk expiry, then (in later subtasks) collision detection,
 * penetration and damage application.</p>
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
 * <p>Each tick the bullet advances {@code bullet_speed} blocks along its
 * current (normalized) direction — no gravity, straight-line flight
 * (设计文档 §位置推进). The step length equals the {@code bullet_speed}
 * attribute value read from the frozen snapshot, so in-flight attribute
 * changes on the gun do not affect already-fired bullets.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletTickHandler {

    /** Attribute id for {@code bullet_speed}, read from the snapshot each tick. */
    private static final ResourceLocation BULLET_SPEED_ID = ModularShootAttributes.BULLET_SPEED.getId();

    /** Attribute id for {@code range}, read from the snapshot each tick. */
    private static final ResourceLocation RANGE_ID = ModularShootAttributes.RANGE.getId();

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
        Collection<BulletRecord> bullets = manager.getAllBullets();
        for (BulletRecord bullet : bullets) {
            processBullet(level, manager, bullet);
        }
    }

    /**
     * Processes a single bullet for one tick, executing the flight pipeline:
     * {@code onTick} hook, aging, position advance, chunk migration, range
     * expiry, unloaded-chunk expiry, then collision/penetration/damage
     * (TODO subtasks 10–12, 14).
     *
     * <p>Returns early once the bullet is removed so no further processing
     * runs on a dead record.</p>
     *
     * @param level   the server level
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet to advance
     */
    private static void processBullet(Level level, BulletManager manager, BulletRecord bullet) {
        // 1. onTick trait hook — TODO(subtask-14): fire ON_TICK callbacks here.

        // 2. age++
        bullet.incrementAge();

        // 3. position advance (records prevPos for chunk migration & future collision)
        Vec3 prevPos = bullet.getPosition();
        advancePosition(bullet);

        // 4. chunk-bucket migration if the bullet crossed a chunk boundary
        checkChunkMigration(level, manager, bullet, prevPos);

        // 5. range expiry — the bullet's lifetime has ended
        if (checkRangeExpiry(manager, bullet)) {
            return;
        }

        // 6. unloaded-chunk expiry — cannot raycast into unloaded space
        if (checkUnloadedChunk(level, manager, bullet)) {
            return;
        }

        // 7. collision detection — TODO(subtask-10): block/entity raycast on [prevPos, curPos].
        // 8. penetration        — TODO(subtask-11): entity/block penetration counts & dedup.
        // 9. damage application — TODO(subtask-12): damage post-processor chain + hurt().
    }

    /**
     * Advances the bullet's position by {@code bullet_speed} blocks along its
     * current direction and accumulates the traveled distance
     * (设计文档 §位置推进).
     *
     * <p>No gravity is applied — flight is straight-line. The direction is
     * assumed normalized (guaranteed at construction); multiplying by
     * {@code stepLength} yields a displacement of exactly {@code stepLength}
     * blocks.</p>
     *
     * @param bullet the bullet to advance
     */
    private static void advancePosition(BulletRecord bullet) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        double stepLength = snapshot.getStat(BULLET_SPEED_ID);
        Vec3 direction = bullet.getDirection();
        Vec3 oldPos = bullet.getPosition();
        Vec3 newPos = oldPos.add(direction.multiply(stepLength, stepLength, stepLength));
        bullet.setPosition(newPos);
        bullet.addTraveledDistance((float) stepLength);
    }

    /**
     * Migrates the bullet's chunk-bucket index if it crossed a chunk boundary
     * during this tick's position advance (设计文档 §空间分区).
     *
     * <p>{@link BulletManager#updateChunkBucket} is a no-op when the old and
     * new chunks are identical, so calling it unconditionally is safe.</p>
     *
     * @param level   the server level (reserved for future collision integration)
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet that may have moved
     * @param prevPos the bullet's position before this tick's advance
     */
    private static void checkChunkMigration(Level level, BulletManager manager, BulletRecord bullet, Vec3 prevPos) {
        ChunkPos oldChunk = toChunkPos(prevPos);
        ChunkPos newChunk = toChunkPos(bullet.getPosition());
        manager.updateChunkBucket(bullet, oldChunk, newChunk);
    }

    /**
     * Checks whether the bullet has exceeded its {@code range}; if so, fires
     * {@code onExpire} (TODO subtask-14) and removes the bullet with
     * {@link RemoveReason#EXPIRED} (设计文档 §范围检查).
     *
     * @param manager the dimension's bullet manager
     * @param bullet  the bullet to check
     * @return {@code true} if the bullet was removed (caller should skip
     *         further processing for this bullet this tick)
     */
    private static boolean checkRangeExpiry(BulletManager manager, BulletRecord bullet) {
        double range = bullet.getSnapshot().getStat(RANGE_ID);
        if (bullet.getTraveledDistance() > range) {
            // TODO(subtask-14): fire ON_EXPIRE trait hook here.
            manager.removeBullet(bullet.getBulletId(), RemoveReason.EXPIRED);
            return true;
        }
        return false;
    }

    /**
     * Checks whether the bullet has advanced into an unloaded chunk; if so,
     * fires {@code onExpire} (TODO subtask-14) and removes the bullet with
     * {@link RemoveReason#UNLOADED_CHUNK} (设计文档 §未加载区块处理).
     *
     * <p>The framework never force-loads chunks for bullet simulation: a
     * bullet entering unloaded space is silently dropped to avoid remote
     * shots triggering large-scale chunk loading.</p>
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
            // TODO(subtask-14): fire ON_EXPIRE trait hook here.
            manager.removeBullet(bullet.getBulletId(), RemoveReason.UNLOADED_CHUNK);
            return true;
        }
        return false;
    }

    /**
     * Converts a world-space position to its containing chunk coordinates.
     * Uses {@link SectionPos#blockToSectionCoord(double)} which floors the
     * coordinate before shifting, handling negative positions correctly.
     *
     * @param pos the world-space position
     * @return the chunk containing that position
     */
    private static ChunkPos toChunkPos(Vec3 pos) {
        return new ChunkPos(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.z));
    }
}
