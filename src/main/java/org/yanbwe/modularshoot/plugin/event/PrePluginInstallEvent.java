package org.yanbwe.modularshoot.plugin.event;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

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
 * cancels it (via {@link #setCanceled(boolean)} or the convenience
 * {@link #cancel(Component)}), the installation is aborted:
 * the plugin item is <strong>not</strong> consumed, the gun's component data is
 * left untouched, and {@code ATTRIBUTE_MODIFIERS} is <strong>not</strong>
 * refreshed. No {@link PostPluginInstallEvent} is fired for this plugin.
 * A listener may attach a custom reason via {@link #cancel(Component)};
 * the framework surfaces it as the install error instead of the generic
 * "blocked" message (审查 E3).</p>
 *
 * <p>Bilateral semantics: the installation flow runs once on each logical
 * side &mdash; the client and the server each execute the full pipeline and
 * each fire this event (一次右键双端各触发一次, 双端独立裁决). Only a
 * <strong>server-side</strong> cancellation prevents the final installation;
 * a client-side cancellation only aborts the local preview and is overwritten
 * by the server's container sync. The instance uuid generated during the
 * installation flow (see {@link PostPluginInstallEvent#getInstanceUuid()}) is
 * derived independently on each side and therefore differs across sides
 * &mdash; do not use it for cross-side bookkeeping; rely on the
 * {@code gun_data} synced from the server instead.</p>
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
    private final ResourceLocation selectedTypeId;
    /** Custom cancellation reason supplied by {@link #cancel(Component)} (审查 E3). */
    @Nullable
    private Component cancelReason;

    /**
     * @param player         the player performing the installation; never {@code null}
     *                       for the install path (installation always requires a player)
     * @param gun            the gun {@link ItemStack} the plugin is about to be
     *                       installed into
     * @param pluginId       the registry id of the plugin being installed
     * @param selectedTypeId the plugin category (slot type) id the framework
     *                       selected for this install (审查 E3 — 监听者可得知/日志化
     *                       目标槽位种类); never {@code null}
     */
    public PrePluginInstallEvent(
            Player player, ItemStack gun, ResourceLocation pluginId, ResourceLocation selectedTypeId) {
        this.player = player;
        this.gun = gun;
        this.pluginId = pluginId;
        this.selectedTypeId = selectedTypeId;
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

    /**
     * @return the plugin category (slot type) id the framework selected for
     *         this install (审查 E3)
     */
    public ResourceLocation getSelectedTypeId() {
        return selectedTypeId;
    }

    /**
     * Cancels the installation with a custom user-facing reason (审查 E3).
     * The framework surfaces this component as the install error instead of
     * the generic "blocked" message.
     *
     * @param reason the user-facing cancellation reason; must not be
     *               {@code null}
     */
    public void cancel(Component reason) {
        this.cancelReason = reason;
        setCanceled(true);
    }

    /**
     * @return the custom cancellation reason attached via
     *         {@link #cancel(Component)}, or {@code null} when the event was
     *         canceled plainly via {@link #setCanceled(boolean)}
     */
    @Nullable
    public Component getCancelReason() {
        return cancelReason;
    }
}
