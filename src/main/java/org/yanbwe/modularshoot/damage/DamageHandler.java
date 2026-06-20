package org.yanbwe.modularshoot.damage;

import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Functional interface for global bullet-damage post-processors
 * (设计文档 §伤害后处理).
 *
 * <p>Third-party mods register handlers via
 * {@link DamageHandlerRegistry#register(DamageHandler)} (exposed publicly as
 * {@code ModularShootAPI.registerDamageHandler}). The framework invokes the
 * registered handlers <b>before</b> the damage is applied to the target —
 * i.e. before {@link DamageApplier#applyDamage(BulletRecord, Entity, double)}
 * calls {@link Entity#hurt(net.minecraft.world.damagesource.DamageSource, float)}.
 * The chain's final return value becomes the actual damage dealt, letting
 * mods implement PvP damage caps, armour-piercing modifiers, distance-based
 * falloff, damage-type-aware reductions, etc.</p>
 *
 * <h2>Responsibility boundary</h2>
 * <p>Per the design contract (设计文档 §职责边界):</p>
 * <ul>
 *   <li><b>Damage handlers</b> (this interface) run <i>before</i> damage
 *       application and decide the final numeric value. They may freely
 *       rewrite the incoming {@code damage} argument.</li>
 *   <li><b>{@code onHit} trait hooks</b> run <i>after</i> damage application
 *       and are for附加效果 (ignite, knockback, particles) only. Modifying
 *       the snapshot's {@code hit_damage} inside an {@code onHit} hook does
 *       <b>not</b> retroactively affect the current hit.</li>
 * </ul>
 *
 * <h2>Chain semantics</h2>
 * <p>Handlers execute in registration order. Each handler receives the
 * output of the previous one (or the base damage for the first handler) and
 * returns the value to feed into the next. A handler that does not wish to
 * modify the damage simply returns its {@code damage} argument unchanged
 * (设计文档 §链式执行).</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ModularShootAPI.registerDamageHandler((bullet, target, damage) -> {
 *     if (target instanceof Player && damage > 20) {
 *         return damage * 0.5; // PvP damage cap
 *     }
 *     return damage; // pass through unchanged
 * });
 * }</pre>
 *
 * @see DamageHandlerRegistry
 * @see DamageApplier
 */
@FunctionalInterface
public interface DamageHandler {

    /**
     * Transforms the current damage value for a bullet hit.
     *
     * <p>Called in registration order by
     * {@link DamageHandlerRegistry#processChain(BulletRecord, Entity, double)}.
     * The {@code damage} parameter is either the base damage (for the first
     * registered handler) or the return value of the previous handler. The
     * returned value is fed to the next handler, or — if this is the last
     * handler — becomes the final damage applied to {@code target}.</p>
     *
     * <p>Implementations should be pure with respect to the damage value:
     * read {@code bullet} and {@code target} for context, compute a new
     * damage number, and return it. Side effects on the target (e.g.
     * applying potion effects) belong in the {@code onHit} hook, not here
     * (设计文档 §职责边界).</p>
     *
     * @param bullet  the bullet record that hit the target (supplies the
     *                attribute snapshot, shooter, damage type, etc.)
     * @param target  the entity hit by the bullet
     * @param damage  the current damage value (base damage for the first
     *                handler, otherwise the previous handler's output)
     * @return the processed damage value to pass to the next handler or to
     *         apply to the target; returning {@code damage} unchanged is a
     *         valid no-op pass-through
     */
    double processDamage(BulletRecord bullet, Entity target, double damage);
}
