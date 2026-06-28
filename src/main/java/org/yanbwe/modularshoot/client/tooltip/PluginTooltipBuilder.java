package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import org.yanbwe.modularshoot.degradation.PluginTypeDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;

/**
 * Entry point for injecting tooltip sections into plugin item
 * ({@code modularshoot:plugin}) tooltips via NeoForge's
 * {@link ItemTooltipEvent} (设计文档 §插件物品的提示文本, lines 1556-1590).
 *
 * <p>Registered on the NeoForge game event bus with
 * {@code value = Dist.CLIENT} so the class is only loaded on the physical
 * client. This prevents {@code ClassNotFoundException} for client-only
 * classes on a dedicated server.</p>
 *
 * <p><b>Tooltip layout injected:</b>
 * <ul>
 *   <li>{@code 可安装至: <categories>} — grey line listing every category
 *       whose tags intersect with the plugin's tags, sorted by category
 *       priority. When the plugin has no tags or every matching category
 *       definition is missing, the line degrades to
 *       {@code 可安装至: 未知} via
 *       {@link PluginTypeDegradationHandler#getDegradedInstallTarget()}
 *       (设计文档 §种类定义丢失降级, line 2361).</li>
 *   <li>{@code <brief>} — grey one-line summary when present.</li>
 *   <li>{@code <按 [Shift] 展开标签>} — hint line; replaced by the tag list
 *       when Shift is held.</li>
 * </ul>
 * </p>
 *
 * <p><b>Main-menu safety:</b> the handler skips injection when the viewing
 * player is {@code null} (e.g. search-tree population on startup), since the
 * plugin type registry is empty on the main menu and per-player registry
 * access requires a live player context.</p>
 *
 * @see TooltipBuilder
 * @see PluginTypeDegradationHandler
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class PluginTooltipBuilder {
    private PluginTooltipBuilder() {
    }

    /**
     * Injects the "可安装至" line, brief summary, and Shift-expanded tag
     * list into a plugin item's tooltip.
     *
     * <p>Guard clauses short-circuit in order of increasing cost:
     * <ol>
     *   <li>Item type check — is the stack a plugin item?</li>
     *   <li>Player check — is a viewing player available (non-null)?</li>
     *   <li>Plugin id check — does the stack carry a {@code plugin_data}
     *       component?</li>
     * </ol>
     * </p>
     *
     * <p>When the plugin definition is missing the tooltip degrades: a grey
     * {@code [失效插件] <path>} name line is added followed by the grey
     * {@code 可安装至: 未知} placeholder, and no brief or tag list is
     * rendered. When the definition is present the "可安装至" line is built
     * by reverse-looking-up every category whose tags intersect the plugin's
     * tags, filtering out missing category definitions via
     * {@link PluginTypeDegradationHandler#filterValidTypes} (设计文档 line
     * 2360).</p>
     *
     * @param event the item tooltip event
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!ModularShootAPI.isPlugin(stack)) {
            return;
        }

        @Nullable Player player = event.getEntity();
        if (player == null) {
            return;
        }

        Optional<ResourceLocation> pluginIdOpt = ModularShootAPI.getPluginId(stack);
        if (pluginIdOpt.isEmpty()) {
            return;
        }

        RegistryAccess registryAccess = player.registryAccess();
        ResourceLocation pluginId = pluginIdOpt.get();
        Optional<PluginDefinition> defOpt =
                ModularShootAPI.getPluginDefinition(registryAccess, pluginId);

        List<Component> lines = new ArrayList<>();
        if (defOpt.isEmpty()) {
            // Plugin definition missing: degrade to [失效插件] + 未知 install target.
            lines.add(Component.literal("[失效插件] " + pluginId.getPath())
                    .withStyle(ChatFormatting.GRAY));
            lines.add(PluginTypeDegradationHandler.getDegradedInstallTarget());
        } else {
            PluginDefinition def = defOpt.get();
            lines.add(buildInstallTargetLine(def, registryAccess));
            addBriefLine(lines, def);
            addTagSection(lines, def);
        }
        event.getToolTip().addAll(lines);
    }

    // ------------------------------------------------------------------
    // 可安装至 line
    // ------------------------------------------------------------------

    /**
     * Builds the grey "可安装至: <categories>" line, or the degraded
     * "可安装至: 未知" placeholder when the plugin has no tags or every
     * matching category definition is missing (设计文档 lines 1565-1567,
     * 2360-2361).
     *
     * @param def            the plugin definition
     * @param registryAccess the runtime registry view
     * @return a grey {@link Component} line
     */
    private static Component buildInstallTargetLine(PluginDefinition def, RegistryAccess registryAccess) {
        List<ResourceLocation> pluginTags = def.tags();
        if (pluginTags.isEmpty()) {
            return PluginTypeDegradationHandler.getDegradedInstallTarget();
        }

        List<MatchedType> matched = findMatchingTypes(pluginTags, registryAccess);
        // filterValidTypes keeps only categories whose definitions still exist.
        // Matched types come from the registry so this is normally a no-op,
        // but the guard documents the degradation contract and protects
        // against future refactors that might source type ids from stored NBT
        // (设计文档 line 2360).
        List<ResourceLocation> validIds = PluginTypeDegradationHandler.filterValidTypes(
                matched.stream().map(MatchedType::id).toList(), registryAccess);
        if (validIds.isEmpty()) {
            return PluginTypeDegradationHandler.getDegradedInstallTarget();
        }

        matched.sort(PluginTooltipBuilder::compareTypes);
        Set<ResourceLocation> validSet = Set.copyOf(validIds);
        MutableComponent line = Component.literal("可安装至: ");
        boolean first = true;
        for (MatchedType entry : matched) {
            if (validSet.contains(entry.id())) {
                if (!first) {
                    line.append(Component.literal("、"));
                }
                first = false;
                line.append(TooltipUtils.resolveText(resolveTypeName(entry.id(), entry.definition())));
            }
        }
        if (first) {
            return PluginTypeDegradationHandler.getDegradedInstallTarget();
        }
        return line.withStyle(ChatFormatting.GRAY);
    }

    /**
     * Reverse-looks-up every plugin category whose tag set intersects with
     * the given plugin tags (设计文档 line 1565).
     *
     * @param pluginTags     the plugin's tag ids
     * @param registryAccess the runtime registry view
     * @return a list of {@link MatchedType} entries; empty when no category
     *         matches or the registry is absent
     */
    private static List<MatchedType> findMatchingTypes(
            List<ResourceLocation> pluginTags, RegistryAccess registryAccess) {
        Set<ResourceLocation> pluginTagSet = Set.copyOf(pluginTags);
        List<MatchedType> matched = new ArrayList<>();
        for (ResourceLocation typeId : PluginTypeRegistry.getAllPluginTypeIds(registryAccess)) {
            PluginTypeRegistry.getPluginType(registryAccess, typeId).ifPresent(typeDef -> {
                if (typeDef.tags().stream().anyMatch(pluginTagSet::contains)) {
                    matched.add(new MatchedType(typeId, typeDef));
                }
            });
        }
        return matched;
    }

    /**
     * Resolves the display name of a plugin category, falling back to the
     * id path when the definition carries no name (设计文档 §本地化).
     *
     * @param typeId the category id
     * @param def    the category definition
     * @return the display name string
     */
    private static String resolveTypeName(ResourceLocation typeId, PluginTypeDefinition def) {
        return def.name().filter(s -> !s.isEmpty()).orElse(typeId.getPath());
    }

    /**
     * Compares two matched categories for "可安装至" ordering.
     *
     * <p>Primary sort key: category {@code priority} descending. Secondary
     * sort key: category id ascending (设计文档 line 1565).</p>
     *
     * @param a the first entry
     * @param b the second entry
     * @return a negative integer if {@code a} should appear before
     *         {@code b}
     */
    private static int compareTypes(MatchedType a, MatchedType b) {
        int byPriority = Integer.compare(b.definition().priority(), a.definition().priority());
        if (byPriority != 0) {
            return byPriority;
        }
        return a.id().compareTo(b.id());
    }

    // ------------------------------------------------------------------
    // Brief & tag section
    // ------------------------------------------------------------------

    /**
     * Adds the grey brief summary line when the plugin definition carries a
     * non-empty brief (设计文档 line 1569).
     *
     * @param lines the tooltip line accumulator
     * @param def   the plugin definition
     */
    private static void addBriefLine(List<Component> lines, PluginDefinition def) {
        def.brief().filter(s -> !s.isEmpty()).ifPresent(brief ->
                lines.add(TooltipUtils.resolveText(brief).withStyle(ChatFormatting.GRAY)));
    }

    /**
     * Adds the Shift-expanded tag list or the collapsed Shift-hint line
     * (设计文档 lines 1571-1587).
     *
     * @param lines the tooltip line accumulator
     * @param def   the plugin definition
     */
    private static void addTagSection(List<Component> lines, PluginDefinition def) {
        if (Screen.hasShiftDown()) {
            lines.add(Component.literal("标签:").withStyle(ChatFormatting.GRAY));
            for (ResourceLocation tag : def.tags()) {
                lines.add(Component.literal("  " + tag).withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("<按 [Shift] 收起标签>").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.literal("<按 [Shift] 展开标签>").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    // ------------------------------------------------------------------
    // Internal data carrier
    // ------------------------------------------------------------------

    /**
     * Immutable carrier for a category id and its definition during the
     * "可安装至" reverse-lookup.
     *
     * @param id         the category id (registry key)
     * @param definition the category definition
     */
    private record MatchedType(
            ResourceLocation id,
            PluginTypeDefinition definition
    ) {
    }
}
