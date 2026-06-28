package org.yanbwe.modularshoot.damage;

import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.bullet.BulletRecord;

/**
 * Applies finalised bullet damage to a hit entity
 * (设计文档 §伤害应用机制 — 清零无敌帧 + 正常 hurt + DamageSource 构造).
 *
 * <p>The framework deliberately <b>does not</b> bypass the vanilla damage
 * pipeline. Instead it clears the target's invulnerability cooldown and then
 * calls the ordinary {@link Entity#hurt(DamageSource, float)} so that armour
 * reduction, resistance, absorption, NeoForge damage events
 * ({@code LivingHurtEvent} / {@code LivingDamageEvent} /
 * {@code LivingShieldBlockEvent}) and death-message/statistics construction
 * all run exactly as for any vanilla damage source. This keeps bullets
 * compatible with third-party damage-recording and RPG-stat mods while still
 * letting penetrating rounds strike successive targets without being blocked
 * by the 10-tick invulnerability window (设计文档 §为何不直接调用
 * actuallyHurt).</p>
 *
 * <h2>DamageSource attacker construction</h2>
 * <p>The {@link DamageSource} is built from the bullet snapshot's
 * {@code damageType} and shooter identity per the design rules
 * (设计文档 §DamageSource 攻击者构造):</p>
 * <ul>
 *   <li><b>shooter resolves to an online entity</b> (player or mob) — that
 *       entity becomes the {@code causingEntity}; death messages read
 *       "X was slain by Y using gun". The {@code directEntity} is left
 *       {@code null} because the bullet itself is not a vanilla entity.</li>
 *   <li><b>shooter is {@code null}</b> (turret / trap with no owner) — a
 *       attacker-less {@code DamageSource} is used; death messages fall back
 *       to the damage type's default translation key.</li>
 *   <li><b>shooter is non-null but offline / unloaded</b> — treated the same
 *       as {@code null} (no attacker).</li>
 * </ul>
 *
 * <h2>Shield blocking</h2>
 * <p>Because the {@code directEntity} is {@code null}, vanilla shield-block
 * logic (which keys off the direct entity's facing) does not engage, so
 * bullets are <b>not</b> blocked by shields by default. Mods that want
 * tactical shields should handle this in a damage processor or a
 * {@code LivingShieldBlockEvent} listener (设计文档 §盾牌格挡).</p>
 *
 * <p>This is a stateless utility class; all methods are static and it is not
 * instantiable.</p>
 */
public final class DamageApplier {

    private DamageApplier() {
    }

    /**
     * Applies bullet damage to the given target using the vanilla
     * {@code hurt()} flow.
     *
     * <p>Steps (设计文档 §伤害应用机制):</p>
     * <ol>
     *   <li>Clear the target's invulnerability cooldown so the subsequent
     *       {@code hurt()} is not skipped due to {@code invulnerableTime > 0}.</li>
     *   <li>Build a {@link DamageSource} from the bullet snapshot's damage
     *       type and (optionally) resolved shooter.</li>
     *   <li>Call {@link Entity#hurt(DamageSource, float)} with the finalised
     *       damage value, letting the full vanilla pipeline run.</li>
     * </ol>
     *
     * @param bullet      the bullet record (supplies the snapshot)
     * @param target      the entity hit by the bullet
     * @param finalDamage the post-processor damage value to apply
     */
    public static void applyDamage(BulletRecord bullet, Entity target, double finalDamage) {
        clearInvulnerableFrames(target);
        DamageSource damageSource = buildDamageSource(bullet, target);
        // Clamp to non-negative before hurt(): a damage processor returning a
        // negative value would have undefined behaviour in vanilla hurt()
        // (negative damage is handled inconsistently across versions and may
        // heal the target or trigger unexpected event paths). Guard against
        // it explicitly so the contract "applyDamage never heals" holds
        // (W17 fix, 设计文档 §伤害应用机制 — 防御性编程).
        float safeDamage = (float) Math.max(0.0, finalDamage);
        target.hurt(damageSource, safeDamage);
    }

    /**
     * Resets the target's invulnerability cooldown to zero
     * (设计文档 §伤害应用机制 步骤 1).
     *
     * <p>{@link Entity#invulnerableTime} is the public tick counter that
     * {@code LivingEntity.hurt()} checks to skip repeated damage within the
     * 10-tick i-frame window. Setting it to {@code 0} guarantees the
     * immediately following {@code hurt()} call proceeds into the full
     * armour/resistance/event pipeline rather than being short-circuited.
     * This is the equivalent of the design's {@code setInvulnerableTicks(0)}
     * note — vanilla exposes this as a public field rather than a setter on
     * the base {@code Entity}.</p>
     *
     * @param target the entity whose i-frames should be cleared
     */
    private static void clearInvulnerableFrames(Entity target) {
        target.invulnerableTime = 0;
    }

    /**
     * Builds the {@link DamageSource} for a bullet hit from the snapshot's
     * damage type and shooter identity (设计文档 §DamageSource 攻击者构造).
     *
     * <p>The {@code damageType} holder is taken verbatim from the snapshot so
     * that trait hooks which swap the damage type in-flight are honoured. The
     * shooter is resolved to an online entity when possible; if resolution
     * fails (offline / unloaded / {@code null}) an attacker-less source is
     * produced so death messages degrade gracefully.</p>
     *
     * <p><b>Shooter source consistency (W12 fix).</b> The shooter uuid is read
     * from {@link BulletRecord#getShooter()} — the runtime authoritative
     * field — <em>not</em> from {@code bullet.getSnapshot().getShooter()}.
     * This matches {@link CollisionDetector#isSkippableEntity}, which also
     * uses {@code bullet.getShooter()}. The two code paths therefore share
     * the exact same shooter identity, so the entity skipped as "self" during
     * collision is always the same entity credited as the attacker in the
     * {@link DamageSource}. {@link BulletRecord} allows the runtime shooter
     * to differ from the snapshot shooter (independent firing path), so
     * reading the snapshot here would risk a mismatch where the shooter is
     * skipped for collision but a stale snapshot uuid is credited for the
     * kill (or vice versa).</p>
     *
     * @param bullet  the bullet record (supplies the snapshot + runtime shooter)
     * @param target  the hit entity (supplies the level for shooter lookup)
     * @return a {@link DamageSource} ready for {@link Entity#hurt}
     */
    private static DamageSource buildDamageSource(BulletRecord bullet, Entity target) {
        Holder<DamageType> damageType = bullet.getSnapshot().getDamageType();
        UUID shooterUuid = bullet.getShooter();
        Entity shooter = resolveShooterEntity(shooterUuid, target.level());
        if (shooter == null) {
            return new DamageSource(damageType);
        }
        // directEntity = null: the bullet is not a vanilla entity, so vanilla
        // shield-block / projectile-knockback logic keyed on the direct entity
        // does not engage (设计文档 §盾牌格挡). causingEntity = shooter.
        return new DamageSource(damageType, null, shooter);
    }

    /**
     * Resolves a shooter uuid to a currently-loaded entity
     * (设计文档 §DamageSource 攻击者构造 — 在线/离线/null 三态).
     *
     * <p>Lookup order:</p>
     * <ol>
     *   <li>{@link Level#getPlayerByUUID(UUID)} — finds online players on any
     *       level. A non-null result means the shooter is an online player.</li>
     *   <li>{@link ServerLevel#getEntity(UUID)} — falls back to a general
     *       entity lookup for non-player shooters (mobs) that are loaded in
     *       the server level. Only attempted when the level is a
     *       {@link ServerLevel}.</li>
     * </ol>
     *
     * <p>Returns {@code null} when the shooter uuid is {@code null}, the
     * player is offline, or a mob shooter is not loaded — all of which the
     * design treats as "no attacker".</p>
     *
     * @param shooterUuid the shooter uuid from {@link BulletRecord#getShooter()},
     *                    or {@code null}
     * @param level       the level to resolve in (the target's level)
     * @return the resolved shooter entity, or {@code null} if not found
     */
    @Nullable
    private static Entity resolveShooterEntity(@Nullable UUID shooterUuid, Level level) {
        if (shooterUuid == null) {
            return null;
        }
        Player player = level.getPlayerByUUID(shooterUuid);
        if (player != null) {
            return player;
        }
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getEntity(shooterUuid);
        }
        return null;
    }
}
