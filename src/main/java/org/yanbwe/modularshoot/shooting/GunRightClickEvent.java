package org.yanbwe.modularshoot.shooting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired when a player right-clicks while holding a gun item, and no container
 * GUI is open.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus). The
 * framework fires it based on NeoForge's {@code PlayerInteractEvent.RightClickItem}
 * and {@code RightClickEmpty}, but only when the player is <strong>not</strong>
 * inside any container GUI (server-side {@code player.containerMenu} is the
 * default {@code InventoryMenu}). This keeps right-click gun behavior separate
 * from the plugin-install interaction path: when a player right-clicks a gun
 * inside an inventory with a plugin item in hand, the install flow runs
 * instead and this event does <strong>not</strong> fire.</p>
 *
 * <p>The framework provides no default right-click (use) behavior for guns.
 * This event is purely an extension point: other mods listen via
 * {@code @SubscribeEvent} to implement custom behavior such as reloading,
 * firing-mode switching, aiming, etc. If no listener cancels the event, the
 * framework performs no action on its own.</p>
 *
 * <p>This event is {@linkplain ICancellableEvent cancelable}. Canceling it
 * prevents subsequent handlers in the same bus dispatch from receiving the
 * event, allowing a higher-priority listener to claim the right-click action
 * exclusively.</p>
 *
 * <p>The framework does not provide any aim-down-sights (ADS) API or default
 * behavior; such features are left to other mods to implement via this
 * event.</p>
 */
public class GunRightClickEvent extends Event implements ICancellableEvent {
    private final Player player;
    private final ItemStack gun;

    /**
     * @param player the player right-clicking the gun; never {@code null}
     * @param gun    the gun {@link ItemStack} being right-clicked
     */
    public GunRightClickEvent(Player player, ItemStack gun) {
        this.player = player;
        this.gun = gun;
    }

    /**
     * @return the player right-clicking the gun
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack being right-clicked
     */
    public ItemStack getGun() {
        return gun;
    }
}
