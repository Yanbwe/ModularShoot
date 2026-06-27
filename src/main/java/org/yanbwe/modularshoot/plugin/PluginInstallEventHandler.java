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
     *   <li>right-click only ({@link ClickAction#SECONDARY});</li>
     *   <li>carried item is a {@code modularshoot:plugin} with {@code plugin_data};</li>
     *   <li>slot item is a {@code modularshoot:gun};</li>
     *   <li>slot allows modification ({@link Slot#allowModification}).</li>
     * </ol>
     * <p>On success the modified gun copy is placed into the slot via
     * {@link Slot#set}, the consumed plugin copy is placed onto the cursor via
     * {@link SlotAccess#set}, the event is cancelled, and a sound plays.
     * On failure the event is left uncancelled so vanulla swap proceeds.</p>
     *
     * @param event the stacking event fired by the container menu
     */
    @SubscribeEvent
    public void onItemStackedOnOther(net.neoforged.neoforge.event.ItemStackedOnOtherEvent event) {
        Slot slot = event.getSlot();
        SlotAccess access = event.getCarriedSlotAccess();

        // Only handle right-click.
        if (event.getClickAction() != ClickAction.SECONDARY) {
            return;
        }
        // Carried item must be a modulearshoot:plugin with plugin_data.
        ItemStack carriedItem = event.getCarriedItem();
        if (!isPluginStack(carriedItem)) {
            return;
        }
        // Slot item must be a modulearshoot:gun.
        ItemStack stackedOnItem = event.getStackedOnItem();
        if (!stackedOnItem.is(ModularShootItems.GUN_ITEM.get())) {
            return;
        }
        // Slot must allow modification (Apotheosis guard).
        if (!slot.allowModification(event.getPlayer())) {
            return;
        }

        Player player = event.getPlayer();
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
            // Apotheosis-style sound feedback.
            player.playSound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F,
                    1.5F + 0.35F * (1 - 2 * player.getRandom().nextFloat()));
        } else {
            // Install failed — do NOT cancel. Let vanulla swap proceed.
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
