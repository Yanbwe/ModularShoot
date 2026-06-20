package org.yanbwe.modularshoot.damage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Registry and chain executor for {@link DamageHandler}s
 * (设计文档 §伤害后处理).
 *
 * <p>Provides the extension point where third-party mods register global
 * damage post-processors that run <b>before</b> a bullet's damage is
 * applied to the hit entity. The framework itself registers zero handlers
 * by default; gameplay modifiers (PvP caps, falloff, armour-piercing) are
 * intentionally left to other mods.</p>
 *
 * <p>The handler list is a {@link CopyOnWriteArrayList} so that registration
 * from mod init threads and iteration from the server hit path do not
 * require external synchronisation. Registration happens rarely (during mod
 * init) while iteration happens on every bullet hit; the copy-on-write cost
 * is therefore paid on the rare path and iteration is lock-free. This
 * mirrors the concurrency strategy of
 * {@code ShootPredicateRegistry} (设计文档 §注册表并发策略).</p>
 *
 * <h2>Chain execution</h2>
 * <p>Per the design contract (设计文档 §链式执行):</p>
 * <ul>
 *   <li>All registered handlers execute in registration order.</li>
 *   <li>The return value of each handler becomes the input to the next.</li>
 *   <li>The final return value is the damage actually applied to the target
 *       via {@link DamageApplier#applyDamage(BulletRecord, Entity, double)}.</li>
 *   <li>When no handlers are registered, the base damage is returned
 *       unchanged.</li>
 * </ul>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 *
 * @see DamageHandler
 * @see DamageApplier
 */
public final class DamageHandlerRegistry {

    /**
     * Thread-safe list of damage handlers registered by third-party mods.
     */
    private static final List<DamageHandler> HANDLERS = new CopyOnWriteArrayList<>();

    private DamageHandlerRegistry() {
    }

    /**
     * Registers a custom damage handler.
     *
     * <p>Registered handlers are executed by
     * {@link #processChain(BulletRecord, Entity, double)} on every bullet
     * hit, in registration order, before the damage is applied to the
     * target. The handler is appended to the end of the chain, so it runs
     * after all previously registered handlers.</p>
     *
     * <p>Safe to call during mod common-setup; the underlying list is
     * thread-safe.</p>
     *
     * @param handler the damage handler to register; must not be {@code null}
     */
    public static void register(DamageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        HANDLERS.add(handler);
    }

    /**
     * Runs every registered damage handler against a bullet hit, producing
     * the final damage value to apply to the target
     * (设计文档 §链式执行).
     *
     * <p>Handlers are executed in registration order. Each handler receives
     * the output of the previous one (or {@code baseDamage} for the first
     * handler) and its return value is fed to the next. The final handler's
     * return value is returned to the caller, which should pass it to
     * {@link DamageApplier#applyDamage(BulletRecord, Entity, double)} as
     * the actual damage to deal. When no handlers are registered,
     * {@code baseDamage} is returned unchanged.</p>
     *
     * <p>This method performs no damage application itself — it only
     * computes the final numeric value. The caller is responsible for the
     * subsequent {@code hurt()} call (设计文档 §职责边界).</p>
     *
     * @param bullet      the bullet record that hit the target; must not be
     *                    {@code null}
     * @param target      the entity hit by the bullet; must not be
     *                    {@code null}
     * @param baseDamage  the initial damage value before any handlers run
     *                    (typically the snapshot's {@code hit_damage})
     * @return the final damage value after all handlers have processed it,
     *         or {@code baseDamage} unchanged when no handlers are
     *         registered
     */
    public static double processChain(BulletRecord bullet, Entity target, double baseDamage) {
        Objects.requireNonNull(bullet, "bullet");
        Objects.requireNonNull(target, "target");
        double current = baseDamage;
        for (DamageHandler handler : HANDLERS) {
            current = handler.processDamage(bullet, target, current);
        }
        return current;
    }
}
