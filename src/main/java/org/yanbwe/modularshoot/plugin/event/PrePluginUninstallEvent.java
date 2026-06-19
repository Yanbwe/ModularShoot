package org.yanbwe.modularshoot.plugin.event;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Fired just before a plugin is removed from a gun.
 *
 * <p>This event is posted on the {@code NeoForge.EVENT_BUS} (game bus) before
 * the plugin is removed from the gun's data component and before
 * {@code ATTRIBUTE_MODIFIERS} is refreshed.</p>
 *
 * <p>The {@code player} parameter may be {@code null} when the uninstall is
 * triggered by a non-player source (e.g. a command, a datapack action, or
 * automated maintenance). Listeners must handle a {@code null} player
 * gracefully.</p>
 *
 * <p>This event is {@linkplain ICancellableEvent cancelable}. When a listener
 * cancels it (via {@link #setCanceled(boolean)}), this specific plugin is
 * skipped &mdash; it is <strong>not</strong> removed from the gun. In a batch
 * uninstall scenario the framework continues processing the remaining plugins,
 * so cancelling one plugin does not abort the entire batch. No
 * {@link PostPluginUninstallEvent} is fired for a cancelled plugin.</p>
 *
 * @see PostPluginUninstallEvent
 */
public class PrePluginUninstallEvent extends Event implements ICancellableEvent {
    @Nullable
    private final Player player;
    private final ItemStack gun;
    private final UUID instanceUuid;
    private final ResourceLocation pluginId;

    /**
     * @param player       the player performing the uninstall, or {@code null}
     *                     when triggered by a non-player source
     * @param gun          the gun {@link ItemStack} the plugin is about to be
     *                     removed from
     * @param instanceUuid the unique uuid of the plugin instance being removed
     * @param pluginId     the registry id of the plugin being removed
     */
    public PrePluginUninstallEvent(
            @Nullable Player player,
            ItemStack gun,
            UUID instanceUuid,
            ResourceLocation pluginId
    ) {
        this.player = player;
        this.gun = gun;
        this.instanceUuid = instanceUuid;
        this.pluginId = pluginId;
    }

    /**
     * @return the player performing the uninstall, or {@code null} when
     *         triggered by a non-player source
     */
    @Nullable
    public Player getPlayer() {
        return player;
    }

    /**
     * @return the gun item stack the plugin is being removed from
     */
    public ItemStack getGun() {
        return gun;
    }

    /**
     * @return the unique uuid of the plugin instance being removed
     */
    public UUID getInstanceUuid() {
        return instanceUuid;
    }

    /**
     * @return the registry id of the plugin being removed
     */
    public ResourceLocation getPluginId() {
        return pluginId;
    }
}
