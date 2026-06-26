package org.yanbwe.modularshoot.api.event;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Fired on the server when a player presses the reload key (default: R) while
 * holding a gun in the main hand.
 *
 * <p><b>Framework responsibility:</b> the framework only <em>triggers</em>
 * this event — it does <strong>not</strong> implement any reload logic. The
 * framework does not read ammunition state, play reload animations, modify
 * item durability, or perform any other reload-specific behavior. All such
 * behavior is the responsibility of other mods that subscribe to this
 * event.</p>
 *
 * <p><b>Trigger flow:</b> the client detects the reload key press (via
 * {@link org.yanbwe.modularshoot.client.keybind.ReloadKeyBinding}) and
 * forwards it to the server through a
 * {@link org.yanbwe.modularshoot.network.ReloadC2SPacket}. The server-side
 * handler validates that the player's main-hand item is a gun, constructs
 * this event, and posts it on the {@code NeoForge.EVENT_BUS} (game bus).
 * This keeps the server authoritative: a hacked client cannot trigger a
 * reload event for a non-gun item.</p>
 *
 * <p><b>Extension point:</b> other mods listen via
 * {@code @SubscribeEvent} on the {@code NeoForge.EVENT_BUS} to implement
 * concrete reload behavior, such as consuming ammunition from the inventory,
 * playing a reload animation, applying a reload cooldown, etc. If no
 * listener handles the event, the framework performs no action on its
 * own.</p>
 *
 * <p>This event is {@linkplain ICancellableEvent cancelable}. Canceling it
 * prevents subsequent handlers in the same bus dispatch from receiving the
 * event, allowing a higher-priority listener to claim the reload action
 * exclusively.</p>
 *
 * <p>This event is fired on the {@code NeoForge.EVENT_BUS} (game bus), not
 * the mod event bus.</p>
 *
 * @see PlayerEvent
 */
public class ReloadEvent extends PlayerEvent implements ICancellableEvent {

    private final ItemStack gun;

    /**
     * @param player the player pressing the reload key; never {@code null}
     * @param gun    the gun {@link ItemStack} in the player's main hand at the
     *               moment the reload was requested
     */
    public ReloadEvent(Player player, ItemStack gun) {
        super(player);
        this.gun = gun;
    }

    /**
     * Returns the player who triggered the reload request.
     *
     * <p>This is a semantic alias for {@link #getEntity()} (which already
     * returns {@link Player} via covariant return on {@link PlayerEvent}).
     * Provided so listener code reads naturally:
     * {@code event.getPlayer()} rather than {@code event.getEntity()}.</p>
     *
     * @return the player who pressed the reload key
     */
    public Player getPlayer() {
        return getEntity();
    }

    /**
     * Returns the gun item stack in the player's main hand at the moment the
     * reload was requested.
     *
     * @return the gun {@link ItemStack}
     */
    public ItemStack getGun() {
        return gun;
    }
}
