package org.yanbwe.modularshoot.plugin.event;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

/**
 * Fired after a plugin has been successfully installed into a gun.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * the plugin has been written into the gun's data component and after the
 * {@code ATTRIBUTE_MODIFIERS} component has been refreshed, so listeners
 * observe the gun in its final, post-installation state.</p>
 *
 * <p>The {@code instanceUuid} is the unique identifier assigned to the
 * installed plugin instance. It can be used later to identify this specific
 * installation when handling {@link PrePluginUninstallEvent} or
 * {@link PostPluginUninstallEvent}.</p>
 *
 * <p>This event is <strong>not</strong> cancelable. To prevent an installation,
 * listen to {@link PrePluginInstallEvent} instead.</p>
 *
 * @see PrePluginInstallEvent
 */
public class PostPluginInstallEvent extends Event {
    private final Player player;
    private final ItemStack gun;
    private final ResourceLocation pluginId;
    private final UUID instanceUuid;

    /**
     * @param player       the player who performed the installation
     * @param gun          the gun {@link ItemStack} the plugin was installed into
     * @param pluginId     the registry id of the installed plugin
     * @param instanceUuid the unique uuid assigned to the installed plugin instance
     */
    public PostPluginInstallEvent(Player player, ItemStack gun, ResourceLocation pluginId, UUID instanceUuid) {
        this.player = player;
        this.gun = gun;
        this.pluginId = pluginId;
        this.instanceUuid = instanceUuid;
    }

    /**
     * @return the player who performed the installation
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the plugin was installed into
     */
    public ItemStack getGun() {
        return gun;
    }

    /**
     * @return the registry id of the installed plugin
     */
    public ResourceLocation getPluginId() {
        return pluginId;
    }

    /**
     * @return the unique uuid assigned to the installed plugin instance
     */
    public UUID getInstanceUuid() {
        return instanceUuid;
    }
}
