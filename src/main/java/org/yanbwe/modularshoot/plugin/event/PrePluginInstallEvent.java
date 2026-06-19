package org.yanbwe.modularshoot.plugin.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired just before a plugin is written into a gun's component data.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * all installation validations have passed (slot compatibility, exclusive-group
 * conflict, capacity checks) but <strong>before</strong> the plugin is actually
 * written into the gun's data component and before {@code ATTRIBUTE_MODIFIERS}
 * is refreshed.</p>
 *
 * <p>This event is {@linkplain ICancellableEvent cancelable}. When a listener
 * cancels it (via {@link #setCanceled(boolean)}), the installation is aborted:
 * the plugin item is <strong>not</strong> consumed, the gun's component data is
 * left untouched, and {@code ATTRIBUTE_MODIFIERS} is <strong>not</strong>
 * refreshed. No {@link PostPluginInstallEvent} is fired for this plugin.</p>
 *
 * <p>Listeners that only need to observe a completed installation should listen
 * to {@link PostPluginInstallEvent} instead.</p>
 *
 * @see PostPluginInstallEvent
 */
public class PrePluginInstallEvent extends Event implements ICancellableEvent {
    private final Player player;
    private final ItemStack gun;
    private final ResourceLocation pluginId;

    /**
     * @param player    the player performing the installation; never {@code null}
     *                  for the install path (installation always requires a player)
     * @param gun       the gun {@link ItemStack} the plugin is about to be
     *                  installed into
     * @param pluginId  the registry id of the plugin being installed
     */
    public PrePluginInstallEvent(Player player, ItemStack gun, ResourceLocation pluginId) {
        this.player = player;
        this.gun = gun;
        this.pluginId = pluginId;
    }

    /**
     * @return the player performing the installation
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the plugin is being installed into
     */
    public ItemStack getGun() {
        return gun;
    }

    /**
     * @return the registry id of the plugin being installed
     */
    public ResourceLocation getPluginId() {
        return pluginId;
    }
}
