package org.yanbwe.modularshoot.bullet;

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
     * @param direction     initial flight direction (normalized)
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
        this.direction = direction;
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

    public void setDirection(Vec3 direction) {
        this.direction = direction;
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

    /** Returns the dedup set of entity uuids already penetrated by this bullet. */
    public Set<UUID> getPenetratedEntities() {
        return penetratedEntities;
    }

    /** Returns the dedup set of block positions already penetrated by this bullet. */
    public Set<BlockPos> getPenetratedBlocks() {
        return penetratedBlocks;
    }
}
