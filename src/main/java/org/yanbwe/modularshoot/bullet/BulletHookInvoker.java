package org.yanbwe.modularshoot.bullet;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.trait.RemoveReason;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;

/**
 * Dispatches trait runtime hooks to every registered callback
 * (设计文档 §特性运行时钩子).
 *
 * <p>Each {@code fire*} method iterates over the set of trait ids that have
 * registered at least one callback for the <em>specific</em> hook type being
 * fired (queried via
 * {@link TraitHookRegistry#getTraitIdsForHookType(TraitHookType)}), looks up
 * the callbacks for the matching {@link TraitHookType}, and invokes them in
 * registration order. This hook-type-specific iteration avoids querying
 * trait ids that registered only for other hook types, eliminating wasted
 * map lookups at high bullet counts (S4).</p>
 *
 * <p>The framework fires a hook only when its registering trait is
 * <em>active</em> on the bullet: before dispatching, the invoker checks
 * {@link BulletSnapshot#getTrait(ResourceLocation)} for the hook's trait id
 * and skips inactive traits (审查 E2 — 注册即只作用于携带该特性的子弹).
 * This both removes the previous "register and self-filter" trap for
 * third-party mods and eliminates the per-bullet-per-hook-type wasted
 * dispatch at high bullet counts. Callbacks no longer need to re-check
 * their own trait flag.</p>
 *
 * <p>Later callbacks observe modifications made by earlier callbacks to the
 * shared {@link BulletSnapshot}, enabling chain effects such as ramping
 * damage across penetration hits (设计文档 §onHit 链式影响).</p>
 *
 * <p>Exception isolation: a third-party hook that throws an exception is
 * logged and skipped, leaving the remaining hooks and the bullet pipeline
 * unaffected (抛异常的第三方钩子被记录并跳过，不影响其余钩子与子弹管线).</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see TraitHookRegistry
 * @see TraitCallbacks
 * @see TraitHookType
 */
public final class BulletHookInvoker {

    private BulletHookInvoker() {
    }

    /**
     * Fires all registered {@link TraitHookType#ON_TICK} callbacks for the
     * given bullet. Called once per server tick at the start of
     * {@code processBullet}, before aging and position advance
     * (设计文档 §onTick).
     *
     * @param bullet the bullet being ticked; its snapshot is passed to each
     *               callback
     */
    public static void fireOnTick(BulletRecord bullet) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        Set<ResourceLocation> traitIds = TraitHookRegistry.getTraitIdsForHookType(TraitHookType.ON_TICK);
        for (ResourceLocation traitId : traitIds) {
            // 审查 E2: 只对携带该特性的子弹派发，钩子无需自查。
            if (!snapshot.getTrait(traitId)) {
                continue;
            }
            List<TraitCallbacks.TraitTickCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_TICK, TraitCallbacks.TraitTickCallback.class);
            for (TraitCallbacks.TraitTickCallback hook : hooks) {
                try {
                    hook.onTick(bullet, snapshot);
                } catch (Exception e) {
                    ModularShoot.LOGGER.error(
                            "Trait hook {} (type {}) threw an exception; skipping this hook",
                            traitId, TraitHookType.ON_TICK, e);
                }
            }
        }
    }

    /**
     * Fires all registered {@link TraitHookType#ON_HIT} callbacks for the
     * given bullet after it collides with an entity. Damage is applied
     * before this hook fires, so callbacks are used for side effects (ignite,
     * knockback, particles) rather than damage rewriting
     * (设计文档 §onHit — fired after damage is applied).
     *
     * @param bullet the bullet that hit the entity
     * @param target the entity that was hit; never {@code null}
     */
    public static void fireOnHit(BulletRecord bullet, Entity target) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        Set<ResourceLocation> traitIds = TraitHookRegistry.getTraitIdsForHookType(TraitHookType.ON_HIT);
        for (ResourceLocation traitId : traitIds) {
            // 审查 E2: 只对携带该特性的子弹派发，钩子无需自查。
            if (!snapshot.getTrait(traitId)) {
                continue;
            }
            List<TraitCallbacks.TraitHitCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_HIT, TraitCallbacks.TraitHitCallback.class);
            for (TraitCallbacks.TraitHitCallback hook : hooks) {
                try {
                    hook.onHit(bullet, snapshot, target);
                } catch (Exception e) {
                    ModularShoot.LOGGER.error(
                            "Trait hook {} (type {}) threw an exception; skipping this hook",
                            traitId, TraitHookType.ON_HIT, e);
                }
            }
        }
    }

    /**
     * Fires all registered {@link TraitHookType#ON_BLOCK_HIT} callbacks for
     * the given bullet after it collides with a block face
     * (设计文档 §onBlockHit).
     *
     * @param bullet the bullet that hit the block
     * @param pos    the world position of the hit block; never {@code null}
     * @param face   the direction of the impacted face; never {@code null}
     */
    public static void fireOnBlockHit(BulletRecord bullet, BlockPos pos, Direction face) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        Set<ResourceLocation> traitIds = TraitHookRegistry.getTraitIdsForHookType(TraitHookType.ON_BLOCK_HIT);
        for (ResourceLocation traitId : traitIds) {
            // 审查 E2: 只对携带该特性的子弹派发，钩子无需自查。
            if (!snapshot.getTrait(traitId)) {
                continue;
            }
            List<TraitCallbacks.TraitBlockHitCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_BLOCK_HIT, TraitCallbacks.TraitBlockHitCallback.class);
            for (TraitCallbacks.TraitBlockHitCallback hook : hooks) {
                try {
                    hook.onBlockHit(bullet, snapshot, pos, face);
                } catch (Exception e) {
                    ModularShoot.LOGGER.error(
                            "Trait hook {} (type {}) threw an exception; skipping this hook",
                            traitId, TraitHookType.ON_BLOCK_HIT, e);
                }
            }
        }
    }

    /**
     * Fires all registered {@link TraitHookType#ON_EXPIRE} callbacks for the
     * given bullet. This is the "lifetime expired" specialist hook, fired
     * only when the bullet exceeds its {@code range}; other removal causes
     * do not trigger it. After this hook completes,
     * {@link #fireOnRemove} fires with {@link RemoveReason#EXPIRED}
     * (设计文档 §onExpire 与 onRemove 的触发关系).
     *
     * @param bullet the bullet whose lifetime has ended
     */
    public static void fireOnExpire(BulletRecord bullet) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        Set<ResourceLocation> traitIds = TraitHookRegistry.getTraitIdsForHookType(TraitHookType.ON_EXPIRE);
        for (ResourceLocation traitId : traitIds) {
            // 审查 E2: 只对携带该特性的子弹派发，钩子无需自查。
            if (!snapshot.getTrait(traitId)) {
                continue;
            }
            List<TraitCallbacks.TraitExpireCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_EXPIRE, TraitCallbacks.TraitExpireCallback.class);
            for (TraitCallbacks.TraitExpireCallback hook : hooks) {
                try {
                    hook.onExpire(bullet, snapshot);
                } catch (Exception e) {
                    ModularShoot.LOGGER.error(
                            "Trait hook {} (type {}) threw an exception; skipping this hook",
                            traitId, TraitHookType.ON_EXPIRE, e);
                }
            }
        }
    }

    /**
     * Fires all registered {@link TraitHookType#ON_REMOVE} callbacks for the
     * given bullet before it is evicted from the manager. This is the
     * universal cleanup hook; the {@code reason} parameter distinguishes the
     * removal scenario. Called for every removal cause
     * (设计文档 §onRemove).
     *
     * @param bullet  the bullet about to be removed
     * @param reason  why the bullet is being removed; never {@code null}
     */
    public static void fireOnRemove(BulletRecord bullet, RemoveReason reason) {
        BulletSnapshot snapshot = bullet.getSnapshot();
        Set<ResourceLocation> traitIds = TraitHookRegistry.getTraitIdsForHookType(TraitHookType.ON_REMOVE);
        for (ResourceLocation traitId : traitIds) {
            // 审查 E2: 只对携带该特性的子弹派发，钩子无需自查。
            if (!snapshot.getTrait(traitId)) {
                continue;
            }
            List<TraitCallbacks.TraitRemoveCallback> hooks = TraitHookRegistry.getHooks(
                    traitId, TraitHookType.ON_REMOVE, TraitCallbacks.TraitRemoveCallback.class);
            for (TraitCallbacks.TraitRemoveCallback hook : hooks) {
                try {
                    hook.onRemove(bullet, snapshot, reason);
                } catch (Exception e) {
                    ModularShoot.LOGGER.error(
                            "Trait hook {} (type {}) threw an exception; skipping this hook",
                            traitId, TraitHookType.ON_REMOVE, e);
                }
            }
        }
    }
}
