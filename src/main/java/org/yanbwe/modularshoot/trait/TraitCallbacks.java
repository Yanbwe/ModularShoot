package org.yanbwe.modularshoot.trait;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;

/**
 * Functional interfaces for the six trait runtime hooks (five server-side,
 * one client-side).
 *
 * <p>Each interface corresponds to a {@link TraitHookType} and is invoked by
 * the bullet engine at the matching lifecycle point. Third-party mods
 * implement these interfaces (typically as lambdas) and register them via
 * {@link TraitHookRegistry#register} (设计文档 §特性运行时钩子).</p>
 *
 * <p>All interfaces extend the empty {@link TraitCallback} marker so that
 * {@link TraitHookRegistry} can store heterogeneous callbacks in a single
 * map and validate the callback type against the hook type at registration
 * time.</p>
 *
 * <h2>Lambda usage</h2>
 * <p>Because {@link TraitTickCallback} and {@link TraitExpireCallback} share
 * the same parameter list {@code (BulletRecord, BulletSnapshot)}, an inline
 * lambda passed to
 * {@link TraitHookRegistry#register TraitHookRegistry.register} must be
 * cast to the specific interface so the compiler can infer the target type:</p>
 * <pre>{@code
 * TraitHookRegistry.register(
 *     ResourceLocation.parse("examplemod:ramping_damage"),
 *     TraitHookType.ON_TICK,
 *     (TraitTickCallback) (bullet, snapshot) -> {
 *         if (snapshot.getTrait(ResourceLocation.parse("examplemod:ramping_damage"))) {
 *             double dmg = snapshot.getStat(ModularShootAttributes.HIT_DAMAGE);
 *             snapshot.setStat(ModularShootAttributes.HIT_DAMAGE, dmg + bullet.getAge());
 *         }
 *     }
 * );
 * }</pre>
 * <p>Alternatively, assign the lambda to a typed variable first — the cast is
 * then unnecessary:</p>
 * <pre>{@code
 * TraitTickCallback hook = (bullet, snapshot) -> { ... };
 * TraitHookRegistry.register(id, TraitHookType.ON_TICK, hook);
 * }</pre>
 *
 * @see TraitHookType
 * @see TraitHookRegistry
 */
public final class TraitCallbacks {

    private TraitCallbacks() {
    }

    /**
     * Empty marker supertype for all trait hook callbacks.
     *
     * <p>Implemented by every callback interface in this file so that
     * {@link TraitHookRegistry} can store and retrieve callbacks in a single
     * typed map. This interface declares no methods and is not a functional
     * interface; it exists solely for type unification.</p>
     */
    public interface TraitCallback {
    }

    /**
     * Fired every server tick while the bullet is alive
     * ({@link TraitHookType#ON_TICK}).
     *
     * <p>Hooks may mutate the {@link BulletRecord} (position, direction) to
     * implement custom trajectories, and may mutate the
     * {@link BulletSnapshot} (stats, traits) to implement ramping or
     * conditional attribute changes. Multiple callbacks for the same trait
     * fire in registration order; later callbacks observe earlier
     * modifications (设计文档 §onTick).</p>
     */
    @FunctionalInterface
    public interface TraitTickCallback extends TraitCallback {

        /**
         * @param bullet   the live bullet record (mutable flight state)
         * @param snapshot the bullet snapshot (mutable stats and traits)
         */
        void onTick(BulletRecord bullet, BulletSnapshot snapshot);
    }

    /**
     * Fired when the bullet collides with an entity
     * ({@link TraitHookType#ON_HIT}).
     *
     * <p>Damage is applied to the entity after all {@code ON_HIT} callbacks
     * for this hit have completed, so hooks may adjust
     * {@code hit_damage} or replace the damage type in-flight. Hooks may
     * also record the hit in the bullet's per-bullet state map for
     * downstream penetration logic (设计文档 §onHit).</p>
     */
    @FunctionalInterface
    public interface TraitHitCallback extends TraitCallback {

        /**
         * @param bullet   the live bullet record
         * @param snapshot the bullet snapshot (mutable)
         * @param entity   the entity that was hit; never {@code null}
         */
        void onHit(BulletRecord bullet, BulletSnapshot snapshot, Entity entity);
    }

    /**
     * Fired when the bullet collides with a block face
     * ({@link TraitHookType#ON_BLOCK_HIT}).
     *
     * <p>Receives the exact block position and the direction of the impacted
     * face. Hooks may spawn impact effects, modify penetration behaviour,
     * or adjust the bullet's trajectory for ricochet-style traits (设计文档
     * §onBlockHit).</p>
     */
    @FunctionalInterface
    public interface TraitBlockHitCallback extends TraitCallback {

        /**
         * @param bullet    the live bullet record
         * @param snapshot  the bullet snapshot (mutable)
         * @param blockPos  the world position of the hit block; never {@code null}
         * @param direction the face that was impacted; never {@code null}
         */
        void onBlockHit(BulletRecord bullet, BulletSnapshot snapshot, BlockPos blockPos, Direction direction);
    }

    /**
     * Fired when the bullet exceeds its {@code range} and its life ends
     * ({@link TraitHookType#ON_EXPIRE}).
     *
     * <p>This is the "lifetime expired" specialist hook. It fires only for
     * the range-exceeded scenario; other removal causes (hit, unloaded
     * chunk, etc.) do not trigger this hook. After {@code ON_EXPIRE}
     * completes, {@link TraitHookType#ON_REMOVE} fires with
     * {@link RemoveReason#EXPIRED} (设计文档 §onExpire 与 onRemove 的触发关系).</p>
     */
    @FunctionalInterface
    public interface TraitExpireCallback extends TraitCallback {

        /**
         * @param bullet   the live bullet record
         * @param snapshot the bullet snapshot (mutable)
         */
        void onExpire(BulletRecord bullet, BulletSnapshot snapshot);
    }

    /**
     * Fired before the bullet is removed from the manager for any reason
     * ({@link TraitHookType#ON_REMOVE}).
     *
     * <p>This is the universal cleanup hook. The {@link RemoveReason}
     * parameter distinguishes the removal scenario. Listeners that need to
     * release resources in all cases should register here rather than
     * listening to each individual hook (设计文档 §onRemove).</p>
     */
    @FunctionalInterface
    public interface TraitRemoveCallback extends TraitCallback {

        /**
         * @param bullet       the live bullet record
         * @param snapshot     the bullet snapshot (mutable)
         * @param removeReason the reason the bullet is being removed;
         *                     never {@code null}
         */
        void onRemove(BulletRecord bullet, BulletSnapshot snapshot, RemoveReason removeReason);
    }

    /**
     * Fired every render frame on the client before a bullet is drawn
     * ({@link TraitHookType#ON_VISUAL_TICK}).
     *
     * <p>This is the only client-side hook. It runs once per frame (not per
     * server tick) and is intended for smooth visual mutations: swapping the
     * projectile texture, adjusting scale, or switching between billboard and
     * 3d render modes (设计文档 §特性视觉钩子).</p>
     *
     * <p><strong>Parameter type contract.</strong> The {@code renderObject}
     * parameter is typed {@link Object} so that this common interface does
     * not depend on the client-only
     * {@code org.yanbwe.modularshoot.client.render.BulletRenderObject} class
     * &mdash; referencing a client class here would cause a
     * {@code ClassNotFoundException} on a dedicated server that loads this
     * common module. On the client the actual argument is always a
     * {@code BulletRenderObject}; implementations cast it back before use:</p>
     * <pre>{@code
     * TraitHookRegistry.register(
     *     ResourceLocation.parse("examplemod:glowing_bullet"),
     *     TraitHookType.ON_VISUAL_TICK,
     *     (TraitVisualTickCallback) renderObject -> {
     *         BulletRenderObject ro = (BulletRenderObject) renderObject;
     *         ro.setScale(1.5f);
     *     }
     * );
     * }</pre>
     * <p>The dispatch is driven by
     * {@code org.yanbwe.modularshoot.client.render.VisualTickHookDispatcher},
     * which passes the typed {@code BulletRenderObject} on the client.</p>
     */
    @FunctionalInterface
    public interface TraitVisualTickCallback extends TraitCallback {

        /**
         * @param renderObject the client-side {@code BulletRenderObject},
         *                     passed as {@link Object} to avoid a
         *                     common-module dependency on client classes;
         *                     never {@code null} on the client
         */
        void onVisualTick(Object renderObject);
    }
}
