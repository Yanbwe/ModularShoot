package org.yanbwe.modularshoot.plugin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Container-GUI right-click handler that triggers plugin installation
 * (设计文档 §安装交互, lines 466-510).
 *
 * <p>Listens to {@link ItemStackedOnOtherEvent} on the NeoForge game bus. When
 * the player right-clicks ({@link ClickAction#SECONDARY}) a
 * {@code modularshoot:gun} stack in a container slot while carrying a
 * {@code modularshoot:plugin} stack on the cursor, the handler delegates to
 * {@link PluginInstallService#installPlugin} to run the full validation +
 * write pipeline.</p>
 *
 * <p>Interaction routing:</p>
 * <ul>
 *   <li><b>Plugin on gun, right-click</b> &mdash; the event is always canceled
 *       to suppress the vanilla item-swap, regardless of whether the install
 *       succeeds or fails. The install logic itself runs only on the server;
 *       the client cancels purely to prevent a stale swap prediction.</li>
 *   <li><b>Non-plugin cursor or non-gun target, or left-click</b> &mdash; the
 *       event is left untouched so vanilla container behaviour applies.</li>
 * </ul>
 *
 * <p>On a validation failure the player is notified via the action bar using
 * the {@link #INSTALL_FAILED_KEY} translation key. The class is registered on
 * the game bus via {@link EventBusSubscriber} with no {@code bus} parameter,
 * matching the pattern used by
 * {@link org.yanbwe.modularshoot.event.OffhandRestrictionHandler}.</p>
 *
 * @see PluginInstallService
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class PluginInstallEventHandler {

    /** Translation key for the action-bar warning shown when installation fails. */
    public static final String INSTALL_FAILED_KEY = "modularshoot.install_failed";

    private PluginInstallEventHandler() {
    }

    /**
     * Routes container right-click stacking into the plugin install pipeline.
     *
     * <p>Filter order (设计文档 lines 466-510):</p>
     * <ol>
     *   <li>right-click only ({@link ClickAction#SECONDARY});</li>
     *   <li>carried item is a {@code modularshoot:plugin} with
     *       {@link org.yanbwe.modularshoot.component.PluginData};</li>
     *   <li>slot item is a {@code modularshoot:gun}.</li>
     * </ol>
     * <p>When all three match the event is canceled and, on the server,
     * {@link PluginInstallService#installPlugin} is invoked. A failing result
     * is surfaced to the player via the action bar.</p>
     *
     * @param event the stacking event fired by the container menu
     */
    @SubscribeEvent
    public static void onItemStackedOnOther(ItemStackedOnOtherEvent event) {
        // 2. Only handle right-click (secondary action).
        if (event.getClickAction() != ClickAction.SECONDARY) {
            return;
        }
        // 3. The carried item (cursor) must be a modularshoot:plugin.
        ItemStack carriedItem = event.getCarriedItem();
        if (!isPluginStack(carriedItem)) {
            return;
        }
        // 4. The slot item must be a modularshoot:gun.
        ItemStack stackedOnItem = event.getStackedOnItem();
        if (!stackedOnItem.is(ModularShootItems.GUN_ITEM.get())) {
            return;
        }
        // Plugin + gun interaction: cancel to suppress the vanilla swap on both
        // sides, regardless of whether the install later succeeds or fails.
        event.setCanceled(true);
        // 5. The install pipeline runs only on the authoritative server.
        Player player = event.getPlayer();
        if (player.level().isClientSide()) {
            return;
        }
        // 6. Attempt the installation.
        RegistryAccess registryAccess = player.level().registryAccess();
        ValidationResult result =
                PluginInstallService.installPlugin(stackedOnItem, carriedItem, player, registryAccess);
        // 7. On failure, notify the player via the action bar, appending the
        //    specific error message from ValidationResult when present.
        if (!result.valid()) {
            Component message = Component.translatable(INSTALL_FAILED_KEY);
            if (result.errorMessage().isPresent()) {
                message = Component.empty()
                        .append(message)
                        .append(Component.literal(": " + result.errorMessage().get()));
            }
            player.displayClientMessage(message, true);
        }
    }

    /**
     * Checks whether a stack is a framework plugin item carrying
     * {@link org.yanbwe.modularshoot.component.PluginData}.
     *
     * <p>All plugins share the single {@code modularshoot:plugin} item id, so
     * the item id alone is not enough &mdash; the {@code plugin_data} component
     * must also be present to identify the stack as a bound plugin.</p>
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
