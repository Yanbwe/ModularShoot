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
 * <p>As of the modifier-stacking redesign (设计规格 §2.1 "创建瞬间冻结"),
 * this record also caches the bullet's {@link ComposedBulletStyle} computed
 * once at creation by {@link VisualCompositionService#compose}. Subsequent
 * {@code BulletSyncService.toFullBulletEntry} invocations re-read this
 * cached value rather than recomputing the composition each full-entry
 * sync — the composition is frozen for the bullet's entire lifetime, while
 * in-flight {@code onVisualTick} hooks that want to mutate appearance operate
 * on the client-side {@code BulletRenderObject} directly (spec §7.2 end).</p>
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
    private final ComposedBulletStyle composedStyle;

    private final Set<UUID> penetratedEntities = new HashSet<>();
    private final Set<BlockPos> penetratedBlocks = new HashSet<>();

    /** Cached unmodifiable views of the dedup sets (wrappers delegate live, created once). */
    private final Set<UUID> penetratedEntitiesView = Collections.unmodifiableSet(penetratedEntities);
    private final Set<BlockPos> penetratedBlocksView = Collections.unmodifiableSet(penetratedBlocks);

    /**
     * Optional spatial-index position listener, attached by the owning
     * {@link BulletManager} when the bullet is registered. {@link #setPosition}
     * fires this listener whenever the bullet's position actually changes so
     * the manager can move the bullet between chunk buckets when it crosses a
     * chunk boundary. Any mutation path — the tick driver or a third-party
     * trait hook calling {@link #setPosition} — automatically keeps the index
     * consistent. {@code volatile} so cross-thread visibility is safe.
     */
    private volatile PositionChangeListener positionListener;

    /**
     * @param snapshot      the frozen attribute/trait snapshot
     * @param shooter       shooter uuid (may differ from snapshot for independent firing), or {@code null}
     * @param position      initial world position
     * @param direction     initial flight direction; <em>automatically normalised</em>
     *                      (a zero-length vector degrades to {@link Vec3#ZERO})
     * @param bulletId      unique-per-dimension bullet id assigned by BulletManager
     * @param composedStyle frozen visual composition computed once at creation
     *                      (设计规格 §2.1); never {@code null} — both bullet
     *                      creation call sites
     *                      ({@code ShootingEngine.registerBullet},
     *                      {@code BulletManager.fireBullet}) supply a freshly
     *                      composed style from
     *                      {@link VisualCompositionService#compose}
     */
    public BulletRecord(
            BulletSnapshot snapshot,
            @Nullable UUID shooter,
            Vec3 position,
            Vec3 direction,
            int bulletId,
            ComposedBulletStyle composedStyle) {
        this.snapshot = snapshot;
        this.shooter = shooter;
        this.position = position;
        this.direction = direction.normalize();
        this.traveledDistance = 0f;
        this.age = 0;
        this.bulletId = bulletId;
        this.composedStyle = java.util.Objects.requireNonNull(composedStyle,
                "composedStyle must not be null — both bullet-creation sites supply one");
    }

    public BulletSnapshot getSnapshot() {
        return snapshot;
    }

    /** @return the creation-frozen visual composition; never {@code null}. */
    public ComposedBulletStyle getComposedStyle() {
        return composedStyle;
    }

    @Nullable
    public UUID getShooter() {
        return shooter;
    }

    public Vec3 getPosition() {
        return position;
    }

    /**
     * Sets the bullet's world position, notifying the owning spatial index
     * (via {@link PositionChangeListener}) when the position actually changes
     * so that cross-chunk movement re-buckets the bullet. The listener is
     * attached by {@link BulletManager#addBullet}; when absent (a bullet not
     * yet registered with a manager) this is a plain field write.
     *
     * @param position the new world position; its chunk coordinate is derived
     *                 from the x/z components
     */
    public void setPosition(Vec3 position) {
        Vec3 oldPos = this.position;
        this.position = position;
        PositionChangeListener listener = this.positionListener;
        if (listener != null && !oldPos.equals(position)) {
            listener.onPositionChanged(this, oldPos, position);
        }
    }

    /**
     * Returns the spatial-index position listener currently attached, or
     * {@code null} when the bullet is not registered with a manager.
     *
     * @return the current listener, or {@code null}
     */
    @Nullable
    PositionChangeListener getPositionListener() {
        return positionListener;
    }

    /**
     * Replaces the spatial-index position listener. Called by
     * {@link BulletManager} when registering (attach) and removing (detach) a
     * bullet. Package-private: only the manager owns this wiring.
     *
     * @param listener the new listener, or {@code null} to detach
     */
    void setPositionListener(@Nullable PositionChangeListener listener) {
        this.positionListener = listener;
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
     * <p>The view is created once at construction and delegates live to the underlying set.</p>
     *
     * @return an unmodifiable view of the penetrated-entity uuid set
     */
    public Set<UUID> getPenetratedEntities() {
        return penetratedEntitiesView;
    }

    /**
     * Returns an <em>unmodifiable view</em> of the dedup set of block
     * positions already penetrated by this bullet (W14 fix).
     *
     * <p>The returned {@link Set} rejects all mutating operations. Callers
     * that need to record a newly-penetrated block must use
     * {@link #addPenetratedBlock} — the only sanctioned write path.</p>
     *
     * <p>The view is created once at construction and delegates live to the underlying set.</p>
     *
     * @return an unmodifiable view of the penetrated-block position set
     */
    public Set<BlockPos> getPenetratedBlocks() {
        return penetratedBlocksView;
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

    /**
     * Callback notified by {@link BulletRecord#setPosition} whenever the
     * bullet's position changes, so the owning {@link BulletManager} can keep
     * the per-chunk spatial index consistent across chunk boundaries
     * (阶段 2 / 任务 2.1). Package-private: only {@link BulletManager} wires
     * and consumes it.
     */
    @FunctionalInterface
    interface PositionChangeListener {
        /**
         * Invoked after a bullet's position has been updated.
         *
         * @param bullet the bullet whose position changed
         * @param oldPos the previous position
         * @param newPos the new position
         */
        void onPositionChanged(BulletRecord bullet, Vec3 oldPos, Vec3 newPos);
    }
}
