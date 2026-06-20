package org.yanbwe.modularshoot.client.tooltip;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Entry point for injecting ModularShoot tooltip sections into gun item
 * tooltips via NeoForge's {@link ItemTooltipEvent} (设计文档 §tooltip 集成).
 *
 * <p>Registered on the NeoForge game event bus with
 * {@code value = Dist.CLIENT} so the class is only loaded on the physical
 * client. This prevents {@code ClassNotFoundException} for client-only
 * classes on a dedicated server.</p>
 *
 * <p><b>M5 scope:</b> this builder injects only the state bar via
 * {@link StateTooltipBuilder}. Attribute, trait, and plugin tooltip bars
 * are handled by separate builders in later milestones.</p>
 *
 * <p><b>Main-menu safety:</b> {@link ItemTooltipEvent} can fire with a
 * {@code null} player during search-tree population (e.g. on startup). The
 * handler skips injection in that case, since the state registry is empty
 * on the main menu and per-player states require a live player context.</p>
 *
 * @see StateTooltipBuilder
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class TooltipBuilder {
    private TooltipBuilder() {
    }

    /**
     * Injects the state bar into a gun item's tooltip lines.
     *
     * <p>Guard clauses short-circuit in order of increasing cost:
     * <ol>
     *   <li>Item type check — is the stack a gun?</li>
     *   <li>Player check — is a viewing player available (non-null)?</li>
     *   <li>State bar build — collect, filter, sort, and render.</li>
     * </ol>
     * </p>
     *
     * <p>When the state bar is non-empty, a grey {@code "状态:"} header is
     * added followed by the state lines. When no state is displayable
     * (empty registry, all values at default with {@code hide_default},
     * etc.) the entire section is omitted.</p>
     *
     * @param event the item tooltip event
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!ModularShootAPI.isGun(stack)) {
            return;
        }

        @Nullable Player player = event.getEntity();
        if (player == null) {
            return;
        }

        RegistryAccess registryAccess = player.registryAccess();

        List<Component> stateBar = StateTooltipBuilder.buildStateBar(stack, player, registryAccess);
        if (stateBar.isEmpty()) {
            return;
        }

        event.getToolTip().add(Component.literal("状态:").withStyle(ChatFormatting.GRAY));
        event.getToolTip().addAll(stateBar);
    }
}
