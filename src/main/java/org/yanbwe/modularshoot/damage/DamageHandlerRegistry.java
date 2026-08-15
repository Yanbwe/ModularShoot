package org.yanbwe.modularshoot.damage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import org.yanbwe.modularshoot.ModularShoot;
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

    /**
     * Per-handler-class minute bucket of the last emitted error log, so a
     * handler that throws on consecutive bullet hits cannot flood the log
     * (阶段 7 / 任务 7.1). Mirrors the rate-limit pattern used by
     * {@code StateWarnLogger} / {@code GunDegradationHandler}.
     */
    private static final Map<String, Long> LAST_ERROR_BUCKETS = new ConcurrentHashMap<>();

    /**
     * One minute in milliseconds; used to bucket handler error logs.
     *
     * <p><b>Whole-minute bucket semantics (审查 Low):</b> the throttle compares
     * bucket ids computed as {@code now / ERROR_INTERVAL_MS}, so it is <em>at
     * most one log per whole minute bucket</em> — not a strict "once per 60s"
     * sliding window. A throw late in bucket N and one early in bucket N+1
     * (seconds apart across a minute boundary) can both log; this is the
     * documented convergence for an intentionally cheap integer comparison.</p>
     */
    private static final long ERROR_INTERVAL_MS = 60_000L;

    private DamageHandlerRegistry() {
    }

    /**
     * Resets the shared rate-limit state.
     *
     * <p>Package-private: used by unit tests to reset the static
     * {@link #LAST_ERROR_BUCKETS} between cases. Not intended as a runtime
     * API.</p>
     */
    static void resetRateLimitState() {
        LAST_ERROR_BUCKETS.clear();
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
     * <p>Exception isolation: a handler that throws an exception is logged
     * and skipped; the chain continues with the last successfully computed
     * value (抛异常的第三方 handler 被记录并跳过，链以最近一次成功结果继续).
     * The error log is rate-limited to at most once per minute bucket per
     * handler class, so a handler that throws on every consecutive bullet hit
     * cannot flood the log (阶段 7 / 任务 7.1).</p>
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
        // No handlers registered: short-circuit and return the base damage
        // unmodified, avoiding an empty iteration (阶段 7 / 任务 7.1).
        // The empty-list handling lives in runChain so it is unit-testable
        // headlessly (阶段 7 / 任务 7.1 — 最小可测 seam).
        return runChain(HANDLERS, bullet, target, baseDamage);
    }

    /**
     * Executes a damage-handler chain over {@code handlers}, returning the
     * final damage value.
     *
     * <p>Extracted as the pure, headless-testable core of
     * {@link #processChain(BulletRecord, Entity, double)} (阶段 7 /
     * 任务 7.1 — 最小可测 seam): it takes the handler list explicitly so tests
     * can exercise the empty-list, exception-swallowing, and chain-continue
     * behaviour without a real {@link Entity} (the seam may receive a
     * {@code null} target; production passes the hit entity). An empty list
     * returns {@code baseDamage} unchanged; a throwing handler is logged at
     * most once per minute bucket (via {@link #logExceptionRateLimited}) and
     * skipped, letting the rest of the chain continue with the last
     * successfully computed value.</p>
     *
     * @param handlers   the handler chain to execute (may be empty)
     * @param bullet     the bullet record; must not be {@code null}
     * @param target     the hit entity (may be {@code null} when called from
     *                   tests that ignore the target)
     * @param baseDamage the initial damage value
     * @return the final damage after the whole chain, or {@code baseDamage}
     *         unchanged when {@code handlers} is empty
     */
    static double runChain(
            List<DamageHandler> handlers, BulletRecord bullet, Entity target, double baseDamage) {
        if (handlers.isEmpty()) {
            return baseDamage;
        }
        double current = baseDamage;
        for (DamageHandler handler : handlers) {
            try {
                current = handler.processDamage(bullet, target, current);
            } catch (Exception e) {
                logExceptionRateLimited(handler, e);
            }
        }
        return current;
    }

    /**
     * Logs a handler exception at most once per minute bucket per handler
     * class, so a continuously-throwing handler cannot flood the log on
     * consecutive bullet hits (阶段 7 / 任务 7.1).
     *
     * @param handler the handler that threw
     * @param e       the thrown exception
     */
    private static void logExceptionRateLimited(DamageHandler handler, Exception e) {
        final String key = handler.getClass().getName();
        final long currentBucket = System.currentTimeMillis() / ERROR_INTERVAL_MS;
        if (shouldLog(key, currentBucket)) {
            ModularShoot.LOGGER.error(
                    "DamageHandler {} threw an exception; skipping this handler",
                    handler.getClass().getSimpleName(), e);
        }
    }

    /**
     * Rate-limit decision: whether an error log may be emitted for
     * {@code handlerKey} in the given {@code currentBucket}.
     *
     * <p>The throttle uses <em>whole-minute bucket</em> semantics (阶段 7 /
     * 任务 7.1 — 审查 Low): {@code currentBucket = now / 60_000}, so at most
     * one log is emitted per handler per minute <em>bucket</em> — i.e. at most
     * roughly once per minute, but a hit late in bucket N and an early hit in
     * bucket N+1 (a few ms apart across a minute boundary) may both log. This
     * is not a sliding 60-second window; it is an intentionally cheap integer
     * comparison. Returns {@code true} the first time a key is seen in a
     * bucket and records that bucket; {@code false} on any later call within
     * the same bucket.</p>
     *
     * <p>Extracted as a headless-testable seam (阶段 7 / 任务 7.1 — 最小可测
     * seam): the decision is purely a bucket map lookup, so it is tested with
     * a controllable {@code currentBucket} instead of {@code System.currentTimeMillis()}.</p>
     *
     * @param handlerKey    the handler class name
     * @param currentBucket the whole-minute bucket for "now"
     * @return {@code true} to emit the log for this bucket; {@code false} to
     *         suppress it (already logged in this bucket)
     */
    static boolean shouldLog(String handlerKey, long currentBucket) {
        final Long last = LAST_ERROR_BUCKETS.get(handlerKey);
        if (last != null && last.longValue() == currentBucket) {
            return false;
        }
        LAST_ERROR_BUCKETS.put(handlerKey, currentBucket);
        return true;
    }
}
