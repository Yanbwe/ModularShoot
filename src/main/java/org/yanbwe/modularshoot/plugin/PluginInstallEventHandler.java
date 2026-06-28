package org.yanbwe.modularshoot.plugin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Container-GUI right-click handler that triggers plugin installation,
 * following the Apotheosis gem-socketing pattern.
 *
 * <p>Registered manually via {@code NeoForge.EVENT_BUS.register(new PluginInstallEventHandler())}
 * in {@link org.yanbwe.modularshoot.ModularShoot}. Listens to
 * {@link net.neoforged.neoforge.event.ItemStackedOnOtherEvent}.</p>
 *
 * <p>Interaction routing (matching Apotheosis {@code AdventureEvents.stackedOnOther}):</p>
 * <ul>
 *   <li>Right-click + plugin-on-gun → validate, and on success write results
 *       via {@link Slot#set} / {@link SlotAccess#set}, cancel the event,
 *       and play a sound. On failure the event is left uncancelled so the
 *       vanilla swap proceeds normally.</li>
 *   <li>All other cursor/slot combinations → ignored.</li>
 * </ul>
 *
 * <p>Unlike the previous design, this handler does <strong>not</strong>
 * discriminate between client and server — the install logic runs identically
 * on both sides, matching how Apotheosis handles gem socketing. Because
 * {@link PluginInstallService#installPlugin} now operates on <strong>copies</strong>
 * and the results are written via {@code slot.set()} / {@code access.set()},
 * container sync handles the rest.</p>
 *
 * <h2>Known limitations</h2>
 * <ul>
 *   <li><b>Creative menu (W5).</b> {@link net.neoforged.neoforge.event.ItemStackedOnOtherEvent}
 *       fires only on the client inside the creative inventory menu; the
 *       server never receives the event, so any install performed there would
 *       not persist. Per the event's own Javadoc advice ("listeners should
 *       require the player to be in survival mode if using capabilities that
 *       are not synced"), this handler skips installation entirely when the
 *       player is in creative mode. Creative players should obtain
 *       pre-configured guns via {@code /give} or the creative search tab
 *       instead.</li>
 *   <li><b>Sound feedback (W7).</b> The install sound is played only on the
 *       server side (and synced to the client) to avoid a doubled audible
 *       effect from the bilateral event firing. The random pitch draw is
 *       kept on both sides to preserve player-random alignment (see
 *       {@link PluginInstallService#deriveInstanceUuid}).</li>
 * </ul>
 *
 * @see PluginInstallService
 */
public final class PluginInstallEventHandler {

    /** Translation key for the action-bar warning shown when installation fails. */
    public static final String INSTALL_FAILED_KEY = "modularshoot.install_failed";

    public PluginInstallEventHandler() {
    }

    /**
     * Routes container right-click stacking into the plugin install pipeline.
     *
     * <p>Filter order:</p>
     * <ol>
     *   <li>creative-mode guard — creative players are skipped because the
     *       event fires only on the client in the creative menu, so an
     *       install would not persist on the server (W5 fix);</li>
     *   <li>right-click only ({@link ClickAction#SECONDARY});</li>
     *   <li>carried item is a {@code modularshoot:plugin} with {@code plugin_data};</li>
     *   <li>slot item is a {@code modularshoot:gun};</li>
     *   <li>slot allows modification ({@link Slot#allowModification}).</li>
     * </ol>
     * <p>On success the modified gun copy is placed into the slot via
     * {@link Slot#set}, the consumed plugin copy is placed onto the cursor via
     * {@link SlotAccess#set}, the event is cancelled, and a sound plays
     * (server-side only, synced to the client — W7 fix).
     * On failure the event is left uncancelled so vanilla swap proceeds.</p>
     *
     * @param event the stacking event fired by the container menu
     */
    @SubscribeEvent
    public void onItemStackedOnOther(net.neoforged.neoforge.event.ItemStackedOnOtherEvent event) {
        Player player = event.getPlayer();

        // Creative-mode guard (W5 fix): ItemStackedOnOtherEvent fires only on
        // the client inside the creative menu, so a server-side install never
        // happens and the result would not persist. Skip entirely for creative
        // players, following the event Javadoc's own advice.
        if (player.isCreative()) {
            return;
        }

        Slot slot = event.getSlot();
        SlotAccess access = event.getCarriedSlotAccess();

        // Only handle right-click.
        if (event.getClickAction() != ClickAction.SECONDARY) {
            return;
        }
        // Carried item must be a modularshoot:plugin with plugin_data.
        ItemStack carriedItem = event.getCarriedItem();
        if (!isPluginStack(carriedItem)) {
            return;
        }
        // Slot item must be a modularshoot:gun.
        ItemStack stackedOnItem = event.getStackedOnItem();
        if (!stackedOnItem.is(ModularShootItems.GUN_ITEM.get())) {
            return;
        }
        // Slot must allow modification (Apotheosis guard).
        if (!slot.allowModification(player)) {
            return;
        }

        RegistryAccess registryAccess = player.level().registryAccess();

        // Attempt installation (operates on copies, never mutates originals).
        PluginInstallService.InstallResult result =
                PluginInstallService.installPlugin(stackedOnItem, carriedItem, player, registryAccess);

        if (result.success()) {
            // Write the modified copies back to the container.
            slot.set(result.installedGun());
            access.set(result.consumedPlugin());
            // Suppress the vanilla item swap.
            event.setCanceled(true);
            // Apotheosis-style sound feedback (W7 fix): the pitch is drawn on
            // both sides to keep the player's random source aligned (the
            // install uuid derivation in PluginInstallService relies on
            // bilateral random synchronization), but the sound is only played
            // on the server side (and synced to the client) to avoid a
            // doubled audible effect from the bilateral event firing.
            float pitch = 1.5F + 0.35F * (1 - 2 * player.getRandom().nextFloat());
            if (!player.level().isClientSide()) {
                player.playSound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, pitch);
            }
        } else {
            // Install failed — do NOT cancel. Let vanilla swap proceed.
            // Notify the player via action bar on the server side.
            if (!player.level().isClientSide()) {
                Component message = Component.translatable(INSTALL_FAILED_KEY);
                if (result.errorMessage().isPresent()) {
                    message = Component.empty()
                            .append(message)
                            .append(Component.literal(": " + result.errorMessage().get()));
                }
                player.displayClientMessage(message, true);
            }
        }
    }

    /**
     * Checks whether a stack is a framework plugin item carrying
     * {@link org.yanbwe.modularshoot.component.PluginData}.
     *
     * @param stack the stack to test
     * @return {@code true} when the stack is a {@code modularshoot:plugin} item
     *         with a {@code plugin_data} component
     */
    private static boolean isPluginStack(ItemStack stack) {
        if (!stack.is(ModularShootItems.PLUGIN_ITEM.get())) {
            return false;
        }
        return stack.has(ModularShootDataComponents.PLUGIN_DATA.get());
    }
}
