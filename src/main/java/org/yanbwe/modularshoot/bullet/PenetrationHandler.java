package org.yanbwe.modularshoot.bullet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.trait.RemoveReason;

/**
 * Penetration bookkeeping for in-flight bullets (设计文档 §穿透逻辑).
 *
 * <p>When a bullet collides with an entity or a block, this handler decides
 * whether the bullet <em>penetrates</em> (continues flying) or is <em>removed</em>
 * (penetration exhausted), and maintains the per-bullet dedup sets that prevent
 * the same target from being hit twice by one bullet (设计文档 §穿透去重).</p>
 *
 * <h2>Penetration count semantics</h2>
 * <p>The {@code entity_penetration} / {@code block_penetration} attributes are
 * the <em>maximum number of targets</em> a single bullet may pass through. The
 * count is not a separate decrementing counter on the record; instead the
 * current size of the dedup set is compared against the attribute value read
 * from the snapshot:</p>
 * <ul>
 *   <li>{@code entity_penetration = 2} → the bullet may hit up to 2 entities
 *       and keep flying; the 3rd entity hit removes it.</li>
 *   <li>{@code block_penetration = 0} → no block penetration; the first block
 *       hit removes the bullet.</li>
 * </ul>
 * <p>Because the dedup set already records every penetrated target, its size
 * is exactly the number of penetration "charges" consumed so far. This avoids
 * a separate mutable counter on {@link BulletRecord} and keeps the record
 * schema minimal (设计文档 §穿透去重).</p>
 *
 * <h2>Hook ordering</h2>
 * <p>For block hits the {@code onBlockHit} trait hook is fired <em>before</em>
 * the penetration decision, so callbacks observe the impact regardless of
 * whether the bullet subsequently penetrates or is removed
 * (设计文档 §onBlockHit on block hit). The entity-hit hook
 * ({@code onHit}) is fired by the damage-application layer (subtask 12) after
 * damage is applied, and is intentionally not triggered here.</p>
 *
 * <h2>Return-value convention</h2>
 * <p>Both methods return {@code true} when the bullet has been removed (the
 * caller should skip any further processing for this bullet this tick) and
 * {@code false} when the bullet continues flying. This matches the return
 * semantics of {@code BulletTickHandler}'s expiry checks.</p>
 *
 * <p>This is a stateless utility class; all methods are static and the class
 * is not instantiable.</p>
 */
public final class PenetrationHandler {

    /** Attribute id for {@code entity_penetration}, read from the frozen snapshot. */
    private static final ResourceLocation ENTITY_PENETRATION_ID =
            ModularShootAttributes.ENTITY_PENETRATION.getKey().location();

    /** Attribute id for {@code block_penetration}, read from the frozen snapshot. */
    private static final ResourceLocation BLOCK_PENETRATION_ID =
            ModularShootAttributes.BLOCK_PENETRATION.getKey().location();

    private PenetrationHandler() {
    }

    /**
     * Handles a bullet-entity collision, deciding penetration vs removal
     * (设计文档 §穿透逻辑 — entity_penetration count).
     *
     * <p>If the bullet still has penetration "charges" — the number of
     * entities already in the dedup set is below the {@code entity_penetration}
     * attribute value — the entity's uuid is added to the set and the bullet
     * continues flying. Otherwise the bullet is removed with
     * {@link RemoveReason#HIT_ENTITY}.</p>
     *
     * <p>The {@code onHit} trait hook is <em>not</em> fired here; it is the
     * responsibility of the damage-application layer (subtask 12), which runs
     * after this method returns {@code false}.</p>
     *
     * @param bullet  the bullet that hit the entity
     * @param entity  the entity that was hit; never {@code null}
     * @param manager the dimension's bullet manager, used to remove the bullet
     * @return {@code true} if the bullet was removed (penetration exhausted or
     *         no penetration attribute); {@code false} if the bullet continues
     *         flying (successful penetration)
     */
    public static boolean handleEntityHit(BulletRecord bullet, Entity entity, BulletManager manager) {
        int entityPenetration = (int) bullet.getSnapshot().getStat(ENTITY_PENETRATION_ID);
        if (entityPenetration > 0 && bullet.getPenetratedEntities().size() < entityPenetration) {
            bullet.addPenetratedEntity(entity.getUUID());
            return false;
        }
        manager.removeBullet(bullet.getBulletId(), RemoveReason.HIT_ENTITY);
        return true;
    }

    /**
     * Handles a bullet-block collision, deciding penetration vs removal
     * (设计文档 §穿透逻辑 — block_penetration count).
     *
     * <p>The {@code onBlockHit} trait hook is fired <em>first</em> so callbacks
     * observe the impact regardless of the penetration outcome. Then, if the
     * bullet still has penetration "charges" — the number of blocks already in
     * the dedup set is below the {@code block_penetration} attribute value —
     * the block position is added to the set and the bullet continues flying.
     * Otherwise the bullet is removed with {@link RemoveReason#HIT_BLOCK}.</p>
     *
     * @param bullet  the bullet that hit the block
     * @param pos     the world position of the hit block; never {@code null}
     * @param face    the direction of the impacted face; never {@code null}
     * @param manager the dimension's bullet manager, used to remove the bullet
     * @return {@code true} if the bullet was removed (penetration exhausted or
     *         no penetration attribute); {@code false} if the bullet continues
     *         flying (successful penetration)
     */
    public static boolean handleBlockHit(BulletRecord bullet, BlockPos pos, Direction face, BulletManager manager) {
        BulletHookInvoker.fireOnBlockHit(bullet, pos, face);
        int blockPenetration = (int) bullet.getSnapshot().getStat(BLOCK_PENETRATION_ID);
        if (blockPenetration > 0 && bullet.getPenetratedBlocks().size() < blockPenetration) {
            bullet.addPenetratedBlock(pos);
            return false;
        }
        manager.removeBullet(bullet.getBulletId(), RemoveReason.HIT_BLOCK);
        return true;
    }
}
