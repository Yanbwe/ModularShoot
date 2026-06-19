package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired just before a bullet is spawned for a shot, after the gun's fire-rate
 * control has passed.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * the fire-rate cooldown check succeeds but <strong>before</strong> the
 * {@link org.yanbwe.modularshoot.bullet.BulletRecord} is created and registered
 * with the {@code BulletManager}.</p>
 *
 * <p>This event is {@linkplain ICancellableEvent cancelable}. When a listener
 * cancels it (via {@link #setCanceled(boolean)}), the shot is aborted entirely:
 * no bullet is spawned, no {@link PostShootEvent} is fired, and the shot
 * <strong>does not</strong> count toward the gun's fire-rate limit (the
 * cooldown is not consumed). This lets environmental/rule-based blockers
 * (safe-zone gun bans, admin disables, etc.) suppress a shot without penalizing
 * the player's fire rate.</p>
 *
 * <p><strong>Responsibility guidance</strong>: {@code PreShootEvent} is meant
 * for environmental / rule-based shot suppression that does not need to tell
 * the player why. If the suppression reason relates to the player's own state
 * and should be displayed to them (e.g. out of ammo), use a
 * {@code ShootPredicate} instead. Execution order is:
 * {@code ShootPredicate} &rarr; {@code PreShootEvent}.</p>
 *
 * <p>Listeners that only need to observe a completed shot should listen to
 * {@link PostShootEvent} instead.</p>
 *
 * @see PostShootEvent
 */
public class PreShootEvent extends Event implements ICancellableEvent {
    private final Player player;
    private final ItemStack gun;

    /**
     * @param player the player performing the shot; never {@code null} for the
     *               shoot path (shooting always requires a player)
     * @param gun    the gun {@link ItemStack} the shot is being fired from
     */
    public PreShootEvent(Player player, ItemStack gun) {
        this.player = player;
        this.gun = gun;
    }

    /**
     * @return the player performing the shot
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the shot is being fired from
     */
    public ItemStack getGun() {
        return gun;
    }
}
