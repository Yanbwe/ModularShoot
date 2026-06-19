package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime-mutable snapshot of a bullet's state, frozen at firing time.
 *
 * <p>Captures the final attribute values, activated boolean traits, damage
 * type and shooter identity at the moment the bullet is created. The
 * {@code stats}, {@code traits}, {@code damageType} and {@code state} maps are
 * intentionally mutable so that trait runtime hooks ({@code onTick},
 * {@code onHit}, etc.) can modify them in-flight (设计文档 §子弹快照).</p>
 *
 * <p>The shooter, gun id and gun instance uuid are read-only and set only at
 * construction. For independent firing via
 * {@link BulletManager#fireBullet}, {@code gunId} and {@code gunInstanceUuid}
 * are always {@code null}; {@code shooter} depends on the caller-supplied
 * uuid (设计文档 §独立发射的快照字段约定).</p>
 */
public final class BulletSnapshot {

    private final Map<ResourceLocation, Double> stats;
    private final Map<ResourceLocation, Boolean> traits;
    private Holder<DamageType> damageType;
    private final UUID shooter;
    private final ResourceLocation gunId;
    private final UUID gunInstanceUuid;
    private final Map<ResourceLocation, Object> state;

    /**
     * @param stats           attribute id → final value (copied defensively)
     * @param traits          trait id → activated flag (copied defensively)
     * @param damageType      damage type holder used for hurt() calls
     * @param shooter         shooter uuid, or {@code null} for independent firing
     * @param gunId           gun definition id, or {@code null} for independent firing
     * @param gunInstanceUuid gun instance uuid, or {@code null} for independent firing
     * @param state           per-bullet runtime state map (copied defensively)
     */
    public BulletSnapshot(
            Map<ResourceLocation, Double> stats,
            Map<ResourceLocation, Boolean> traits,
            Holder<DamageType> damageType,
            @Nullable UUID shooter,
            @Nullable ResourceLocation gunId,
            @Nullable UUID gunInstanceUuid,
            Map<ResourceLocation, Object> state) {
        this.stats = new HashMap<>(stats);
        this.traits = new HashMap<>(traits);
        this.damageType = damageType;
        this.shooter = shooter;
        this.gunId = gunId;
        this.gunInstanceUuid = gunInstanceUuid;
        this.state = new HashMap<>(state);
    }

    /** Returns the stat value for the given attribute id, or {@code 0.0} if absent. */
    public double getStat(ResourceLocation id) {
        return stats.getOrDefault(id, 0.0);
    }

    /** Sets or overwrites the stat value for the given attribute id. */
    public void setStat(ResourceLocation id, double value) {
        stats.put(id, value);
    }

    /** Returns the trait flag for the given trait id, or {@code false} if absent. */
    public boolean getTrait(ResourceLocation id) {
        return traits.getOrDefault(id, false);
    }

    /** Sets or overwrites the trait flag for the given trait id. */
    public void setTrait(ResourceLocation id, boolean value) {
        traits.put(id, value);
    }

    /** Returns the damage type holder used when applying hurt to a target. */
    public Holder<DamageType> getDamageType() {
        return damageType;
    }

    /** Replaces the damage type holder (callable by trait hooks in-flight). */
    public void setDamageType(Holder<DamageType> damageType) {
        this.damageType = damageType;
    }

    /** Returns the per-bullet state value for the given state id, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> T getState(ResourceLocation id) {
        return (T) state.get(id);
    }

    /** Sets or overwrites the per-bullet state value for the given state id. */
    public <T> void setState(ResourceLocation id, T value) {
        state.put(id, value);
    }

    /** Returns the shooter uuid, or {@code null} for independent firing. */
    @Nullable
    public UUID getShooter() {
        return shooter;
    }

    /** Returns the gun definition id, or {@code null} for independent firing. */
    @Nullable
    public ResourceLocation getGunId() {
        return gunId;
    }

    /** Returns the gun instance uuid, or {@code null} for independent firing. */
    @Nullable
    public UUID getGunInstanceUuid() {
        return gunInstanceUuid;
    }
}
