package org.yanbwe.modularshoot.bullet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable result of a single bullet collision query along one tick's
 * flight step {@code [prevPos, curPos]} (设计文档 §子弹飞行与碰撞检测).
 *
 * <p>Encapsulates the <em>nearest</em> hit found along the step — either a
 * block, an entity, or nothing — together with the distance from the step's
 * start ({@code prevPos}) to the hit point. The distance lets the caller
 * compare block vs entity hits and pick the closer one ("最近命中" semantics:
 * {@code dEntity < dBlock} → entity, {@code dBlock ≤ dEntity} → block).</p>
 *
 * <p>This is a pure data carrier: it carries no behaviour and is safe to share
 * across threads. Use the static factories ({@link #none()}, {@link #entity},
 * {@link #block}) to construct instances so that the {@link HitType} and the
 * nullable payload fields stay consistent.</p>
 *
 * @param hitType    kind of hit (NONE / ENTITY / BLOCK)
 * @param distance   distance from {@code prevPos} to the hit point;
 *                   {@link Double#MAX_VALUE} when {@code hitType == NONE}
 *                   (treated as "infinitely far" so comparison logic can pick
 *                   the nearer of two results without extra null checks)
 * @param hitEntity  the hit entity, or {@code null} unless
 *                   {@code hitType == ENTITY}
 * @param blockPos   the hit block position, or {@code null} unless
 *                   {@code hitType == BLOCK}
 * @param blockFace  the hit block face, or {@code null} unless
 *                   {@code hitType == BLOCK}
 */
public record CollisionResult(
        HitType hitType,
        double distance,
        @Nullable Entity hitEntity,
        @Nullable BlockPos blockPos,
        @Nullable Direction blockFace) {

    /** Kind of collision a bullet step produced. */
    public enum HitType {
        /** No block or entity was hit along the step. */
        NONE,
        /** An entity was the nearest hit along the step. */
        ENTITY,
        /** A block was the nearest hit along the step. */
        BLOCK
    }

    /**
     * @return a no-hit result (distance {@link Double#MAX_VALUE})
     */
    public static CollisionResult none() {
        return new CollisionResult(HitType.NONE, Double.MAX_VALUE, null, null, null);
    }

    /**
     * @param entity   the entity that was hit
     * @param distance distance from {@code prevPos} to the entity hit point
     * @return an entity-hit result
     */
    public static CollisionResult entity(Entity entity, double distance) {
        return new CollisionResult(HitType.ENTITY, distance, entity, null, null);
    }

    /**
     * @param pos      the hit block position
     * @param face     the hit block face
     * @param distance distance from {@code prevPos} to the block hit point
     * @return a block-hit result
     */
    public static CollisionResult block(BlockPos pos, Direction face, double distance) {
        return new CollisionResult(HitType.BLOCK, distance, null, pos, face);
    }
}
