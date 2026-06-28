package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
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
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Entry point for injecting ModularShoot tooltip sections into gun item
 * tooltips via NeoForge's {@link ItemTooltipEvent} (设计文档 §tooltip 集成).
 *
 * <p>Registered on the NeoForge game event bus with
 * {@code value = Dist.CLIENT} so the class is only loaded on the physical
 * client. This prevents {@code ClassNotFoundException} for client-only
 * classes on a dedicated server.</p>
 *
 * <p><b>Scope:</b> this builder injects all four gun-tooltip sections in
 * design-document order: attribute bar via
 * {@link AttributeTooltipBuilder}, trait bar via
 * {@link TraitTooltipBuilder}, state bar via
 * {@link StateTooltipBuilder}, and plugin bar via
 * {@link PluginBarTooltipBuilder} (设计文档 §四级显示层级, lines
 * 1473-1509). Modifier-key hint lines are appended at the bottom. Plugin-item
 * tooltips (items of type {@code modularshoot:plugin}) are handled by a
 * separate subscriber, {@link PluginTooltipBuilder}.</p>
 *
 * <p><b>Main-menu safety:</b> {@link ItemTooltipEvent} can fire with a
 * {@code null} player during search-tree population (e.g. on startup). The
 * handler skips injection in that case, since the state registry is empty
 * on the main menu and per-player states require a live player context.</p>
 *
 * @see AttributeTooltipBuilder
 * @see TraitTooltipBuilder
 * @see StateTooltipBuilder
 * @see PluginBarTooltipBuilder
 * @see PluginTooltipBuilder
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
     * </ol>
     * </p>
     *
     * <p>After the guards, the four tooltip sections are injected in
     * design-document order (设计文档 lines 1473-1509):
     * <ol>
     *   <li>Attribute bar — filtered by default, Ctrl expands all
     *       (设计文档 lines 1478-1483).</li>
     *   <li>Trait bar — filtered by default, Alt expands descriptions
     *       (设计文档 lines 1485-1490).</li>
     *   <li>State bar — filtered by {@code hide_default}, sorted by
     *       priority.</li>
     *   <li>Plugin bar — always shown when the gun has slots; Shift expands
     *       descriptions.</li>
     * </ol>
     * </p>
     *
     * <p>Finally, modifier-key hint lines are appended at the bottom. Each
     * hint appears only when the corresponding modifier key is not held and
     * the section has expandable content (设计文档 lines 1506-1508, 1578).
     * The three modifier keys (Shift, Ctrl, Alt) can be held simultaneously;
     * their expansion effects stack, and a held key's hint is suppressed.</p>
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
        // the degraded name and gunId, skipping all attribute/trait/state/
        // plugin bars (设计文档 §枪械 gunId 失效降级).
        if (GunDegradationHandler.isGunDefinitionMissing(stack, registryAccess)) {
            appendDegradedTooltip(event.getToolTip(), stack);
            return;
        }

        // 1. Attribute bar (设计文档 §属性栏; Ctrl 展开全部属性).
        List<Component> attributeBar = AttributeTooltipBuilder.buildAttributeBar(stack, player, registryAccess);
        if (!attributeBar.isEmpty()) {
            event.getToolTip().addAll(attributeBar);
        }

        // 2. Trait bar (设计文档 §特性栏; Alt 展开特性描述).
        List<Component> traitBar = TraitTooltipBuilder.buildTraitBar(stack, player, registryAccess);
        if (!traitBar.isEmpty()) {
            event.getToolTip().addAll(traitBar);
        }

        // 3. State bar (设计文档 §状态栏; hide_default 过滤). The "状态:"
        //    header is added by StateTooltipBuilder itself, consistent with
        //    the attribute/trait/plugin bars which also self-add their headers.
        List<Component> stateBar = StateTooltipBuilder.buildStateBar(stack, player, registryAccess);
        if (!stateBar.isEmpty()) {
            event.getToolTip().addAll(stateBar);
        }

        // 4. Plugin bar (设计文档 §插件栏; A-03 种类降级 + A-04 插件降级;
        //    Shift 展开插件描述). Plugin slots are always shown per the
        //    design contract, so an empty state bar does not skip this.
        List<Component> pluginBar = PluginBarTooltipBuilder.buildPluginBar(stack, player, registryAccess);
        if (!pluginBar.isEmpty()) {
            event.getToolTip().addAll(pluginBar);
        }

        // Modifier-key hint lines (设计文档 lines 1506-1508, 1578).
        addModifierHints(event.getToolTip(), stack, registryAccess);
    }

    /**
     * Appends modifier-key hint lines to the tooltip (设计文档 lines
     * 1506-1508, 1578).
     *
     * <p>Each hint appears only when the corresponding modifier key is not
     * currently held and the section has expandable content:
     * <ul>
     *   <li>{@code <按 [Ctrl] 展开属性>} — when the {@code attribute_meta}
     *       registry is non-empty (Ctrl expands all attributes).</li>
     *   <li>{@code <按 [Alt] 展开特性>} — when the {@code traits} registry
     *       is non-empty (Alt expands trait descriptions).</li>
     *   <li>{@code <按 [Shift] 展开插件>} — when the gun has at least one
     *       installed plugin (Shift expands plugin descriptions).</li>
     * </ul>
     * </p>
     *
     * <p>The three modifier keys can be held simultaneously; a held key's
     * hint is suppressed because its expansion is already active
     * (设计文档 line 1578).</p>
     *
     * @param toolTip        the tooltip line list to append to
     * @param stack          the gun item stack
     * @param registryAccess the runtime registry view
     */
    private static void addModifierHints(
            List<Component> toolTip, ItemStack stack, RegistryAccess registryAccess) {
        List<Component> hints = new ArrayList<>(3);
        boolean ctrl = Screen.hasControlDown();
        boolean alt = Screen.hasAltDown();
        boolean shift = Screen.hasShiftDown();

        if (!ctrl && hasAttributeMeta(registryAccess)) {
            hints.add(Component.literal("<按 [Ctrl] 展开属性>")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!alt && hasTraits(registryAccess)) {
            hints.add(Component.literal("<按 [Alt] 展开特性>")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!shift && !ModularShootAPI.getInstalledPlugins(stack).isEmpty()) {
            hints.add(Component.literal("<按 [Shift] 展开插件>")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        toolTip.addAll(hints);
    }

    /**
     * Checks whether the {@code attribute_meta} registry contains at least
     * one entry.
     *
     * @param registryAccess the runtime registry view
     * @return {@code true} when the registry exists and is non-empty
     */
    private static boolean hasAttributeMeta(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY)
                .map(reg -> !reg.entrySet().isEmpty())
                .orElse(false);
    }

    /**
     * Checks whether the {@code traits} registry contains at least one
     * entry.
     *
     * @param registryAccess the runtime registry view
     * @return {@code true} when the registry exists and is non-empty
     */
    private static boolean hasTraits(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.TRAITS_KEY)
                .map(reg -> !reg.entrySet().isEmpty())
                .orElse(false);
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
