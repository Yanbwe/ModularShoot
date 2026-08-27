package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
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
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.degradation.GunDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.AttributeMount;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

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
     * Bounded short-term cache for the aggregated gun-tooltip sections
     * (阶段 4 / 任务 4.1). Keyed by stack identity / registry version /
     * modifier keys / mutable-data version (which folds gun/store data,
     * per-player state, the viewing-context booleans and viewer identity)
     * so hovering the same item state reuses the previous build instead of
     * rebuilding every frame.
     */
    private static final TooltipCache CACHE = new TooltipCache();

    /**
     * Checks whether the aggregated tooltip cache must be bypassed for a gun.
     *
     * <p>Player-side guns read attribute values from an external attribute
     * holder resolved through providers, so the same stack can show different
     * values as the holder changes without any stack/registry/version key
     * change. Those guns therefore bypass the aggregate cache; item-side guns
     * continue to use it.</p>
     *
     * @param stack          the gun item stack
     * @param registryAccess the runtime registry view
     * @return {@code true} when the gun declares
     *         {@link AttributeMount#PLAYER}
     */
    static boolean shouldBypassCache(ItemStack stack, RegistryAccess registryAccess) {
        return ModularShootAPI.getAttributeMount(stack, registryAccess)
                .map(mount -> mount == AttributeMount.PLAYER)
                .orElse(false);
    }

    /**
     * Injects ModularShoot tooltip sections into a gun item's tooltip lines.
     *
     * <p>Guard clauses short-circuit in order of increasing cost:
     * <ol>
     *   <li>Item type check — is the stack a gun? (binding-aware: uses the
     *       viewing player's {@code RegistryAccess} when a player is
     *       available; on the main menu, where the player is {@code null},
     *       falls back to the legacy overload)</li>
     *   <li>Player check — is a viewing player available (non-null)?</li>
     *   <li>Degradation check — is the gun definition missing? If so, the
     *       tooltip is replaced with only the degraded name and gunId
     *       (设计文档 §枪械 gunId 失效降级).</li>
     * </ol>
     * </p>
     *
     * <p>After the guards, a binding-channel gun that has not been attached
     * yet (no {@code gun_data} component) gets a grey identity line
     * {@code 枪械: <path>} inserted right below the item name (设计规格 物品
     * 绑定系统 §7.4), and the tooltip ends there — the four bars are skipped
     * because the stack carries no {@code gun_data}/{@code ATTRIBUTE_MODIFIERS}
     * yet. Native guns and guns that already carry the component skip this
     * line so the display stays non-redundant.</p>
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

        @Nullable Player player = event.getEntity();
        // 绑定感知 isGun 判定：有玩家上下文时携带其 registryAccess（绑定 + Java API）；
        // 主菜单预览（player 为 null，如搜索树填充）时退化到旧签名（组件 + Java API 绑定）。
        boolean isGun = player != null
                ? ModularShootAPI.isGun(stack, player.registryAccess())
                : ModularShootAPI.isGun(stack);
        if (!isGun) {
            return;
        }

        if (player == null) {
            return; // 主菜单预览场景：无玩家上下文，不注入 tooltip 段
        }

        RegistryAccess registryAccess = player.registryAccess();

        // Degradation check: when the gun definition is missing, show only
        // the degraded name and gunId, skipping all attribute/trait/state/
        // plugin bars (设计文档 §枪械 gunId 失效降级).
        if (GunDegradationHandler.isGunDefinitionMissing(stack, registryAccess)) {
            appendDegradedTooltip(event.getToolTip(), stack);
            return;
        }

        // 身份标识行 + 定义预览（设计规格 物品绑定系统 §7.4）：绑定通道识别的
        // 枪械在尚未附加 GUN_DATA 组件时，在物品名之下插入灰色 "枪械: <path>"
        // 标识行，并展示枪械定义中注册的基础属性与插槽（"说明书"）——栈上尚无
        // GUN_DATA/ATTRIBUTE_MODIFIERS，运行时数值读出来全是 0，故不注入
        // 属性/特性/状态/插件四栏。进入背包 1 tick 内由服务端附加组件并写入
        // 基础修饰符（BoundGunAttachHandler，设计规格 §5.3），此后显示完整内容。
        // 已附加组件的原生枪械/已转化枪械不显示该行，避免冗余。
        if (!stack.has(ModularShootDataComponents.GUN_DATA.get())) {
            ModularShootAPI.resolveGunId(stack, registryAccess).ifPresent(gunId -> {
                event.getToolTip().add(1,
                        Component.translatable("modularshoot.tooltip.bound_gun")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(gunId.getPath())));
                GunRegistry.getGun(registryAccess, gunId).ifPresent(def -> {
                    List<Component> preview = buildDefinitionPreview(def, registryAccess);
                    if (!preview.isEmpty()) {
                        // 定义预览紧跟标识行插入（索引 2 起）——MC 原版属性行在
                        // 事件触发时已位于列表末尾之后追加的内容之后，用 addAll
                        // 会落到原版属性下方，必须定点插入。
                        event.getToolTip().addAll(2, preview);
                    }
                });
            });
            return;
        }

        // 1..4 + modifier hints: cached as one aggregate (阶段 4 / 任务 4.1).
        // Keyed by stack identity / registry version / modifier keys / mutable-data
        // version so the same item state under the same modifiers reuses the
        // previous build instead of rebuilding the four sections every frame.
        // The mutable-data version (see TooltipVersion) folds store + per-player
        // state + the viewing-context booleans (is-local-main-hand, is-holding-
        // gun) + the viewer's UUID, so per-player changes and main-hand switches
        // also invalidate the aggregate.
        List<Component> bars;
        if (shouldBypassCache(stack, registryAccess)) {
            bars = buildTooltipSections(stack, player, registryAccess);
        } else {
            // TooltipCacheKey is only needed for the cached path; player-side
            // guns bypass the aggregate cache, so defer its construction.
            TooltipCacheKey key = TooltipCacheKey.of(stack, registryAccess,
                    ModifierKeys.controlDown(), ModifierKeys.altDown(), ModifierKeys.shiftDown(),
                    TooltipVersion.mutableDataVersion(stack, player));
            bars = CACHE.getOrCompute(key,
                    () -> buildTooltipSections(stack, player, registryAccess));
        }
        event.getToolTip().addAll(bars);
    }

    /**
     * Builds the aggregated gun-tooltip sections in design-document order:
     * attribute bar, trait bar, state bar, plugin bar, then the modifier-key
     * hint lines (设计文档 lines 1473-1509, 1506-1508, 1578). This is the
     * cached compute function for {@link #CACHE}.
     *
     * @param stack          the gun item stack
     * @param player         the viewing player (non-null when we reach here)
     * @param registryAccess the runtime registry view
     * @return the aggregated non-empty sections and hints, in display order
     */
    private static List<Component> buildTooltipSections(
            ItemStack stack, Player player, RegistryAccess registryAccess) {
        List<Component> sections = new ArrayList<>();

        // 1. Attribute bar (设计文档 §属性栏; Ctrl 展开全部属性).
        sections.addAll(AttributeTooltipBuilder.buildAttributeBar(stack, player, registryAccess));

        // 2. Trait bar (设计文档 §特性栏; Alt 展开特性描述).
        sections.addAll(TraitTooltipBuilder.buildTraitBar(stack, player, registryAccess));

        // 3. State bar (设计文档 §状态栏; hide_default 过滤). The "状态:" header
        //    is added by StateTooltipBuilder itself, consistent with the other
        //    bars. StateTooltipBuilder self-caches its bar separately.
        sections.addAll(StateTooltipBuilder.buildStateBar(stack, player, registryAccess));

        // 4. Plugin bar (设计文档 §插件栏; A-03 种类降级 + A-04 插件降级;
        //    Shift 展开插件描述). Plugin slots are always shown per the design
        //    contract, so an empty state bar does not skip this.
        sections.addAll(PluginBarTooltipBuilder.buildPluginBar(stack, player, registryAccess));

        // Modifier-key hint lines (设计文档 lines 1506-1508, 1578).
        List<Component> hints = new ArrayList<>(3);
        addModifierHints(hints, stack, registryAccess);
        sections.addAll(hints);
        return sections;
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
        boolean ctrl = ModifierKeys.controlDown();
        boolean alt = ModifierKeys.altDown();
        boolean shift = ModifierKeys.shiftDown();

        if (!ctrl && hasAttributeMeta(registryAccess)) {
            hints.add(Component.translatable("modularshoot.tooltip.hint_ctrl_attributes")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!alt && hasTraits(registryAccess)) {
            hints.add(Component.translatable("modularshoot.tooltip.hint_alt_traits")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!shift && !ModularShootAPI.getInstalledPlugins(stack).isEmpty()) {
            hints.add(Component.translatable("modularshoot.tooltip.hint_shift_plugins")
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
        ModularShootAPI.getGunId(stack).ifPresent(gunId ->
                toolTip.add(Component.literal("gunId: " + gunId)
                        .withStyle(ChatFormatting.DARK_GRAY)));
    }

    /**
     * Builds the "definition preview" lines for a binding-channel gun that
     * has not been converted yet (no {@code gun_data} component): the base
     * stats and plugin slots registered on the gun definition, rendered in
     * the same two-space-indent style as the attribute bar (设计规格 物品绑定
     * 系统 §7.4). This is a static preview of the declared values — the
     * runtime bars are skipped because the stack carries no
     * {@code ATTRIBUTE_MODIFIERS} yet. The caller inserts the returned list
     * right below the identity line.
     *
     * @param def            the resolved gun definition
     * @param registryAccess the runtime registry view (plugin-type lookup)
     * @return the preview lines; empty when the definition declares neither
     *         stats nor slots
     */
    private static List<Component> buildDefinitionPreview(
            GunDefinition def, RegistryAccess registryAccess) {
        List<Component> lines = new ArrayList<>();
        def.stats().forEach((attrId, value) -> lines.add(Component.empty()
                .append(Component.literal("  "))
                .append(AttributeTooltipBuilder.resolveAttributeName(attrId))
                .append(Component.literal(": "))
                .append(Component.literal(TooltipUtils.formatValue(value))
                        .withStyle(ChatFormatting.GRAY))));
        def.slots().forEach((typeId, count) -> {
            String typeName = registryAccess.registry(ModularShootRegistries.PLUGIN_TYPES_KEY)
                    .flatMap(reg -> reg.getOptional(typeId))
                    .flatMap(PluginTypeDefinition::name)
                    .filter(name -> !name.isEmpty())
                    .orElse(typeId.getPath());
            lines.add(Component.empty()
                    .append(Component.literal("  "))
                    .append(Component.literal(typeName))
                    .append(Component.literal(" ×" + count).withStyle(ChatFormatting.GRAY)));
        });
        return lines;
    }
}
