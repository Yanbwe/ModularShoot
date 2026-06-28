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
     * <p>Damage is applied to the entity <em>before</em> the {@code ON_HIT}
     * callbacks fire, so hooks cannot retroactively adjust
     * {@code hit_damage} or replace the damage type for the current hit.
     * The {@code ON_HIT} hook is intended for side effects only (ignite,
     * knockback, particles, etc.); use the damage post-processor chain
     * ({@link org.yanbwe.modularshoot.damage.DamageHandlerRegistry}) to
     * rewrite damage values. Modifying {@code hit_damage} inside an
     * {@code onHit} callback does, however, propagate to subsequent
     * penetration targets because they share the same mutable snapshot.
     * Hooks may also record the hit in the bullet's per-bullet state map
     * for downstream penetration logic (设计文档 §onHit).</p>
     *
     * <p><b>中文说明：</b>伤害在 {@code ON_HIT} 回调触发<em>之前</em>应用到
     * 实体，因此钩子无法追溯调整当前命中的 {@code hit_damage} 或伤害类型。
     * {@code ON_HIT} 钩子仅用于副作用（点燃、击退、粒子等）；如需改写伤害
     * 数值请使用伤害后处理器链
     * （{@link org.yanbwe.modularshoot.damage.DamageHandlerRegistry}）。
     * 但在 {@code onHit} 回调中修改 {@code hit_damage} 会传播到后续穿透目标，
     * 因为它们共享同一个可变快照。钩子也可将命中记录到子弹的 per-bullet
     * 状态映射中，供下游穿透逻辑使用（设计文档 §onHit）。</p>
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
     * <p><strong>Parameter type contract.</strong> Both parameters are typed
     * {@link Object} so that this common interface does not depend on
     * client-only classes &mdash; referencing
     * {@code org.yanbwe.modularshoot.network.ClientBulletSnapshot} or
     * {@code org.yanbwe.modularshoot.client.render.BulletRenderObject} here
     * would cause a {@code ClassNotFoundException} on a dedicated server that
     * loads this common module. On the client the actual arguments are always
     * a {@code ClientBulletSnapshot} (carrying the bullet's frozen
     * stats/traits and identity) and a {@code BulletRenderObject} (the
     * mutable render data); implementations cast them back before use:</p>
     * <pre>{@code
     * TraitHookRegistry.register(
     *     ResourceLocation.parse("examplemod:glowing_bullet"),
     *     TraitHookType.ON_VISUAL_TICK,
     *     (TraitVisualTickCallback) (snapshot, renderObject) -> {
     *         ClientBulletSnapshot snap = (ClientBulletSnapshot) snapshot;
     *         BulletRenderObject ro = (BulletRenderObject) renderObject;
     *         if (snap.getTrait(ResourceLocation.parse("examplemod:glowing_bullet"))) {
     *             ro.setScale(1.5f);
     *         }
     *     }
     * );
     * }</pre>
     * <p>The dispatch is driven by
     * {@code org.yanbwe.modularshoot.client.render.VisualTickHookDispatcher},
     * which passes the typed {@code ClientBulletSnapshot} and
     * {@code BulletRenderObject} on the client.</p>
     */
    @FunctionalInterface
    public interface TraitVisualTickCallback extends TraitCallback {

        /**
         * @param snapshot     the client-side {@code ClientBulletSnapshot}
         *                     carrying the bullet's frozen stats/traits and
         *                     identity, passed as {@link Object} to avoid a
         *                     common-module dependency on client classes;
         *                     never {@code null} on the client. Cast to
         *                     {@code org.yanbwe.modularshoot.network.ClientBulletSnapshot}
         *                     before use.
         * @param renderObject the client-side {@code BulletRenderObject},
         *                     passed as {@link Object} to avoid a
         *                     common-module dependency on client classes;
         *                     never {@code null} on the client
         */
        void onVisualTick(Object snapshot, Object renderObject);
    }
}
