package org.yanbwe.modularshoot.plugin.event;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Fired after a plugin has been successfully removed from a gun.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) after
 * the plugin has been removed from the gun's data component and after the
 * {@code ATTRIBUTE_MODIFIERS} component has been refreshed, so listeners
 * observe the gun in its final, post-uninstall state.</p>
 *
 * <p>The {@code player} parameter may be {@code null} when the uninstall was
 * triggered by a non-player source (e.g. a command, a datapack action, or
 * automated maintenance). Listeners must handle a {@code null} player
 * gracefully.</p>
 *
 * <p>The {@code instanceUuid} identifies the plugin instance that was just
 * removed. It is no longer present in the gun's component data at the time
 * this event fires.</p>
 *
 * <p>This event is <strong>not</strong> cancelable. To prevent an uninstall,
 * listen to {@link PrePluginUninstallEvent} instead.</p>
 *
 * @see PrePluginUninstallEvent
 */
public class PostPluginUninstallEvent extends Event {
    @Nullable
    private final Player player;
    private final ItemStack gun;
    private final ResourceLocation pluginId;
    private final UUID instanceUuid;

    /**
     * @param player       the player who performed the uninstall, or {@code null}
     *                     when triggered by a non-player source
     * @param gun          the gun {@link ItemStack} the plugin was removed from
     * @param pluginId     the registry id of the removed plugin
     * @param instanceUuid the unique uuid of the plugin instance that was removed
     */
    public PostPluginUninstallEvent(
            @Nullable Player player,
            ItemStack gun,
            ResourceLocation pluginId,
            UUID instanceUuid
    ) {
        this.player = player;
        this.gun = gun;
        this.pluginId = pluginId;
        this.instanceUuid = instanceUuid;
    }

    /**
     * @return the player who performed the uninstall, or {@code null} when
     *         triggered by a non-player source
     */
    @Nullable
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the plugin was removed from
     */
    public ItemStack getGun() {
        return gun;
    }

    /**
     * @return the registry id of the removed plugin
     */
    public ResourceLocation getPluginId() {
        return pluginId;
    }

    /**
     * @return the unique uuid of the plugin instance that was removed
     */
    public UUID getInstanceUuid() {
        return instanceUuid;
    }
}
