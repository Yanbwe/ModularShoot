package org.yanbwe.modularshoot.bullet;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Per-bullet runtime record managed by {@link BulletManager}.
 *
 * <p>Holds the immutable-at-construction {@link BulletSnapshot} plus the
 * mutable flight state: position, direction, traveled distance and age. The
 * position and direction are mutable so that trait hooks can implement custom
 * trajectories (tracking rounds, curved paths, etc.) (设计文档 §BulletRecord).</p>
 *
 * <p>Penetration dedup sets ({@code penetratedEntities},
 * {@code penetratedBlocks}) are per-bullet lifetime and prevent the same
 * target from being hit twice by one bullet (设计文档 §穿透去重).</p>
 */
public final class BulletRecord {

    private final BulletSnapshot snapshot;
    private final UUID shooter;
    private Vec3 position;
    private Vec3 direction;
    private float traveledDistance;
    private int age;
    private final int bulletId;

    private final Set<UUID> penetratedEntities = new HashSet<>();
    private final Set<BlockPos> penetratedBlocks = new HashSet<>();

    /**
     * @param snapshot      the frozen attribute/trait snapshot
     * @param shooter       shooter uuid (may differ from snapshot for independent firing), or {@code null}
     * @param position      initial world position
     * @param direction     initial flight direction; <em>automatically normalised</em>
     *                      (a zero-length vector degrades to {@link Vec3#ZERO})
     * @param bulletId      unique-per-dimension bullet id assigned by BulletManager
     */
    public BulletRecord(
            BulletSnapshot snapshot,
            @Nullable UUID shooter,
            Vec3 position,
            Vec3 direction,
            int bulletId) {
        this.snapshot = snapshot;
        this.shooter = shooter;
        this.position = position;
        this.direction = direction.normalize();
        this.traveledDistance = 0f;
        this.age = 0;
        this.bulletId = bulletId;
    }

    public BulletSnapshot getSnapshot() {
        return snapshot;
    }

    @Nullable
    public UUID getShooter() {
        return shooter;
    }

    public Vec3 getPosition() {
        return position;
    }

    public void setPosition(Vec3 position) {
        this.position = position;
    }

    public Vec3 getDirection() {
        return direction;
    }

    /**
     * Sets the bullet's flight direction, <em>automatically normalising</em>
     * the supplied vector (W11 fix).
     *
     * <p>Step-length and hit-distance calculations both assume a unit-length
     * direction vector. Trait hooks that call this method with a non-normalised
     * vector (e.g. a raw {@code target.subtract(self)} displacement) would
     * otherwise produce oversized steps and wrong hit points. Normalising here
     * is the single defensive choke-point that keeps the invariant regardless
     * of caller. A zero-length vector degrades to {@link Vec3#ZERO} (the
     * vanilla {@link Vec3#normalize()} contract), which leaves the bullet
     * stationary rather than producing {@code NaN} components.</p>
     *
     * @param direction the new flight direction; need not be pre-normalised
     */
    public void setDirection(Vec3 direction) {
        this.direction = direction.normalize();
    }

    public float getTraveledDistance() {
        return traveledDistance;
    }

    /** Accumulates the step length traveled this tick. */
    public void addTraveledDistance(float delta) {
        this.traveledDistance += delta;
    }

    public int getAge() {
        return age;
    }

    /** Increments the age counter by one tick. */
    public void incrementAge() {
        this.age++;
    }

    public int getBulletId() {
        return bulletId;
    }

    /**
     * Returns an <em>unmodifiable view</em> of the dedup set of entity uuids
     * already penetrated by this bullet (W14 fix).
     *
     * <p>The returned {@link Set} rejects all mutating operations
     * ({@code add}, {@code remove}, {@code clear}). Callers that need to
     * record a newly-penetrated entity must use {@link #addPenetratedEntity}
     * — the only sanctioned write path. This prevents external code from
     * arbitrarily corrupting the dedup bookkeeping that
     * {@link CollisionDetector} relies on.</p>
     *
     * @return an unmodifiable view of the penetrated-entity uuid set
     */
    public Set<UUID> getPenetratedEntities() {
        return Collections.unmodifiableSet(penetratedEntities);
    }

    /**
     * Returns an <em>unmodifiable view</em> of the dedup set of block
     * positions already penetrated by this bullet (W14 fix).
     *
     * <p>The returned {@link Set} rejects all mutating operations. Callers
     * that need to record a newly-penetrated block must use
     * {@link #addPenetratedBlock} — the only sanctioned write path.</p>
     *
     * @return an unmodifiable view of the penetrated-block position set
     */
    public Set<BlockPos> getPenetratedBlocks() {
        return Collections.unmodifiableSet(penetratedBlocks);
    }

    /**
     * Records an entity as penetrated by this bullet (W14 fix — sanctioned
     * write path for the entity dedup set).
     *
     * <p>This is the only method that may mutate the internal
     * {@code penetratedEntities} set. It is called by
     * {@link PenetrationHandler#handleEntityHit} after a successful
     * penetration decision.</p>
     *
     * @param entityUuid the uuid of the entity to record; must not be {@code null}
     */
    public void addPenetratedEntity(UUID entityUuid) {
        penetratedEntities.add(entityUuid);
    }

    /**
     * Records a block as penetrated by this bullet (W14 fix — sanctioned
     * write path for the block dedup set).
     *
     * <p>This is the only method that may mutate the internal
     * {@code penetratedBlocks} set. It is called by
     * {@link PenetrationHandler#handleBlockHit} after a successful
     * penetration decision.</p>
     *
     * @param pos the block position to record; must not be {@code null}
     */
    public void addPenetratedBlock(BlockPos pos) {
        penetratedBlocks.add(pos);
    }
}
