package org.yanbwe.modularshoot.client.tooltip;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.degradation.GunDegradationHandler;

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
     * Injects ModularShoot tooltip sections into a gun item's tooltip lines.
     *
     * <p>Guard clauses short-circuit in order of increasing cost:
     * <ol>
     *   <li>Item type check — is the stack a gun?</li>
     *   <li>Player check — is a viewing player available (non-null)?</li>
     *   <li>Degradation check — is the gun definition missing? If so, the
     *       tooltip is replaced with only the degraded name and gunId
     *       (设计文档 §枪械 gunId 失效降级).</li>
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

        // Degradation check: when the gun definition is missing, show only
        // the degraded name and gunId, skipping all state/attribute/trait/
        // plugin bars (设计文档 §枪械 gunId 失效降级).
        if (GunDegradationHandler.isGunDefinitionMissing(stack, registryAccess)) {
            appendDegradedTooltip(event.getToolTip(), stack);
            return;
        }

        List<Component> stateBar = StateTooltipBuilder.buildStateBar(stack, player, registryAccess);
        if (stateBar.isEmpty()) {
            return;
        }

        event.getToolTip().add(Component.literal("状态:").withStyle(ChatFormatting.GRAY));
        event.getToolTip().addAll(stateBar);
    }

    /**
     * Replaces the entire tooltip with the minimal degraded lines: the grey
     * "未知枪械" name and the full gunId in dark grey.
     *
     * <p>The vanilla item-name line and any other previously-added lines are
     * cleared so that the tooltip shows <em>only</em> the degraded name and
     * gunId, per the design contract (设计文档 §提示文本降级: 仅显示
     * [未知枪械] 与 gunId). No state, attribute, trait, or plugin bars are
     * added.</p>
     *
     * @param toolTip the tooltip line list to replace
     * @param stack   the degraded gun stack
     */
    private static void appendDegradedTooltip(List<Component> toolTip, ItemStack stack) {
        toolTip.clear();
        toolTip.add(GunDegradationHandler.getDegradedName(stack));
        ResourceLocation gunId = ModularShootAPI.getGunId(stack);
        if (gunId != null) {
            toolTip.add(Component.literal("gunId: " + gunId)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
