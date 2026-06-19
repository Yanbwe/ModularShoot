package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Functional interface for custom shoot predicates.
 *
 * <p>Third-party mods register implementations of this interface via
 * {@link ShootPredicateRegistry#register(ShootPredicate)} to append bespoke
 * pre-shoot conditions such as ammo availability, heat build-up or cooldown
 * timers. All registered predicates run <strong>after</strong> the framework's
 * fire-rate control passes and <strong>before</strong> the
 * {@code PreShootEvent} is fired (设计文档 §射击时序步骤三). The first
 * predicate that returns a failing {@link ShootPredicateResult} aborts the
 * shot and its reason is shown to the player on the action bar.</p>
 *
 * <p><strong>Responsibility split with {@code PreShootEvent}</strong>: use a
 * {@code ShootPredicate} when the blockage is tied to the player's or gun's
 * own state and the player should be told <em>why</em> the shot is blocked
 * (e.g. "out of ammo", "overheated"). Use {@code PreShootEvent} for
 * environmental or rule-based suppression that does not require a player-facing
 * explanation (e.g. "safe zone", "dimension gun ban"). Both mechanisms run on
 * every shot; predicates first, then the event.</p>
 *
 * <p>Implementations should be pure and side-effect free: they receive the
 * player and gun stack as read-only inputs and must return a
 * {@link ShootPredicateResult} without mutating the stack, the player, or any
 * global state. Heavy work should be minimised since predicates run on the
 * server shoot path for every shot.</p>
 *
 * <p>The framework registers zero predicates by default (设计文档
 * §射击条件判断); the ammo system is intentionally left to third-party
 * mods.</p>
 */
@FunctionalInterface
public interface ShootPredicate {

    /**
     * Checks whether the given player may fire the given gun at this moment.
     *
     * @param player the player attempting to shoot; must not be {@code null}
     * @param gun    the gun item stack being fired; carries {@code gun_data}
     *               when it is a {@code modularshoot:gun} stack
     * @return a {@link ShootPredicateResult};
     *         {@link ShootPredicateResult#success()} to allow the shot, or
     *         {@link ShootPredicateResult#failure(String)} with a player-facing
     *         reason to abort it
     */
    ShootPredicateResult test(Player player, ItemStack gun);
}
