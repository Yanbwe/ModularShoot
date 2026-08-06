package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;

/**
 * Functional interface for per-pellet shoot effects (机制三 效果贡献者, 规格 §5).
 *
 * <p>Third-party mods register implementations of this interface via
 * {@link ShootEffectRegistry#register(ShootEffect)} to apply stackable,
 * per-pellet modifications to a shot — e.g. a 10% chance to activate a boolean
 * trait or a 10% chance to double the bullet's damage. Unlike the variant pool
 * (机制四), effects are <em>additive</em>: every registered effect runs on the
 * same pellet and their mutations compose instead of competing for a single
 * winner.</p>
 *
 * <p><strong>Execution timing (规格 §5.1)</strong>: effects run inside the
 * step-seven pellet loop, once per pellet, right after the pellet's snapshot
 * {@link BulletSnapshot#copy()} and right before spread application. The
 * {@code pelletIndex}/{@code totalPellets} pair therefore reaches every
 * effect, so the index may be used as a per-pellet differentiation seed
 * (规则弹幕 / 逐颗分化). Effects execute in registration order; a later effect
 * sees all snapshot mutations made by earlier ones.</p>
 *
 * <p><strong>Usage red lines (规格 §5.2)</strong>: recommended mutation
 * methods are {@code setTrait} (boolean traits stack naturally),
 * {@code multiplyStat} (multiplicative scaling composes) and {@code setStat}
 * (deliberate, deterministic overwrite). Mutating <em>exclusive single-value</em>
 * fields — {@code setDamageType} or the visual {@code base} — is forbidden:
 * when several mods randomly overwrite the same exclusive field the result is
 * field-level fragmentation (the exact problem the variant pool was designed
 * to solve). Exclusive effects must go through the variant pool (机制四)
 * instead. This is a documented contract, not a hard technical block.</p>
 *
 * <p>Implementations should be pure and side-effect free apart from mutating
 * the supplied snapshot: they receive the player and gun stack as read-only
 * inputs and must not mutate the stack, the player, or any global state. Heavy
 * work should be minimised since effects run on the server shoot path for
 * every pellet of every shot.</p>
 *
 * <p>The framework registers zero effects by default (规格 §5); probabilistic
 * gameplay effects are intentionally left to third-party mods.</p>
 */
@FunctionalInterface
public interface ShootEffect {

    /**
     * Applies this effect to one pellet's snapshot.
     *
     * @param player       the shooting player; must not be {@code null}
     * @param gun          the gun item stack being fired; carries
     *                     {@code gun_data} when it is a
     *                     {@code modularshoot:gun} stack
     * @param snapshot     this pellet's copied snapshot; effects mutate it in
     *                     place and must not retain it beyond the call
     * @param pelletIndex  zero-based index of this pellet within the shot
     *                     (0..{@code totalPellets}-1); usable as a per-pellet
     *                     differentiation seed
     * @param totalPellets total number of pellets in this shot (clamped to the
     *                     engine's {@code MAX_PELLETS}); always at least 1
     */
    void apply(Player player, ItemStack gun, BulletSnapshot snapshot, int pelletIndex, int totalPellets);
}
