package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.degradation.PluginTypeDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;

/**
 * Builds the plugin-bar section of a gun's tooltip (设计文档 §tooltip 集成,
 * lines 1472-1511).
 *
 * <p>Iterates the gun's slot configuration (category id → slot count),
 * rendering one header line per category — {@code [类型名]（已安装数/插槽数）}
 * — followed by the installed plugins grouped under that category. Categories
 * are sorted by {@code priority} descending and are always shown, even when
 * empty (设计文档 line 1473, 1476).</p>
 *
 * <p><b>Degradation handling:</b>
 * <ul>
 *   <li><b>A-03 — missing category definition:</b> installed plugins whose
 *       {@code installedTypeId} points to a removed category definition are
 *       grouped into a trailing grey {@code [未知种类]} section via
 *       {@link PluginTypeDegradationHandler#groupPluginsByType} (设计文档
 *       line 2367). The section is always last and uses
 *       {@link PluginTypeDegradationHandler#getDegradedTypeName()} as its
 *       header.</li>
 *   <li><b>A-04 — missing plugin definition:</b> an installed plugin whose
 *       {@code pluginId} no longer resolves is rendered as a grey
 *       {@code [失效插件] <path>} line with no brief or description, via
 *       {@link PluginDegradationHandler#getDegradedPluginName} (设计文档
 *       line 2356).</li>
 * </ul>
 * </p>
 *
 * <p>Default view shows {@code 插件名 - brief}; Shift view expands to
 * {@code 插件名} followed by {@code |description} (or {@code |brief} when
 * no description is set) (设计文档 lines 1474, 1500-1502).</p>
 *
 * @see TooltipBuilder
 * @see PluginTypeDegradationHandler
 * @see PluginDegradationHandler
 */
public final class PluginBarTooltipBuilder {
    private PluginBarTooltipBuilder() {
    }

    /**
     * Builds the plugin-bar tooltip lines for a gun stack.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Resolve the gun definition; return empty when missing (the
     *       {@link TooltipBuilder} already replaced the tooltip with the
     *       degraded name in that case).</li>
     *   <li>Read the gun's slot configuration; return empty when the gun
     *       has no plugin slots.</li>
     *   <li>Group installed plugins by category via
     *       {@link PluginTypeDegradationHandler#groupPluginsByType} —
     *       missing-category instances are placed in the trailing
     *       {@link PluginTypeDegradationHandler#UNKNOWN_TYPE_ID} group
     *       (A-03, 设计文档 line 2367).</li>
     *   <li>Render every configured category header (sorted by priority),
     *       followed by its plugins; then render the trailing
     *       {@code [未知种类]} group when non-empty.</li>
     * </ol>
     * </p>
     *
     * @param gunStack       the gun item stack to read installed plugins from
     * @param viewingPlayer  the player viewing the tooltip (unused for state,
     *                       reserved for future per-player filtering); may be
     *                       {@code null}
     * @param registryAccess the runtime registry view for definition lookups
     * @return an ordered list of plugin-bar components; empty when the gun
     *         has no slot configuration or its definition is missing
     */
    public static List<Component> buildPluginBar(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        ResourceLocation gunId = ModularShootAPI.getGunId(gunStack);
        if (gunId == null) {
            return List.of();
        }
        Optional<GunDefinition> gunDefOpt =
                ModularShootAPI.getGunDefinition(registryAccess, gunId);
        if (gunDefOpt.isEmpty()) {
            // Gun definition missing: TooltipBuilder already replaced the
            // tooltip with the degraded name; no plugin bar is rendered.
            return List.of();
        }

        GunDefinition gunDef = gunDefOpt.get();
        Map<ResourceLocation, Integer> slots = gunDef.slots();
        if (slots.isEmpty()) {
            return List.of();
        }

        List<PluginInstance> installed = ModularShootAPI.getInstalledPlugins(gunStack);
        // A-03: group installed plugins by category; missing-category
        // instances are placed in the trailing UNKNOWN_TYPE_ID group
        // (设计文档 line 2367).
        Map<ResourceLocation, List<PluginInstance>> grouped =
                PluginTypeDegradationHandler.groupPluginsByType(installed, registryAccess);

        boolean shift = Screen.hasShiftDown();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("插件:").withStyle(ChatFormatting.GRAY));

        // Render every configured category, sorted by priority descending
        // (设计文档 line 1473).
        List<SlotEntry> slotEntries = collectSlotEntries(slots, registryAccess);
        for (SlotEntry entry : slotEntries) {
            int installedCount = grouped.getOrDefault(entry.typeId(), List.of()).size();
            lines.add(buildTypeHeader(entry, installedCount));
            List<PluginInstance> plugins = grouped.getOrDefault(entry.typeId(), List.of());
            for (PluginInstance plugin : plugins) {
                lines.addAll(buildPluginLines(plugin, registryAccess, shift));
            }
        }

        // A-03: trailing [未知种类] group for plugins whose category
        // definition is missing (设计文档 line 2367).
        List<PluginInstance> unknownPlugins = grouped.get(PluginTypeDegradationHandler.UNKNOWN_TYPE_ID);
        if (unknownPlugins != null && !unknownPlugins.isEmpty()) {
            lines.add(PluginTypeDegradationHandler.getDegradedTypeName());
            for (PluginInstance plugin : unknownPlugins) {
                lines.addAll(buildPluginLines(plugin, registryAccess, shift));
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Slot entries
    // ------------------------------------------------------------------

    /**
     * Collects the gun's slot configuration entries, sorted by category
     * priority descending then by category id ascending (设计文档 line 1473).
     *
     * <p>Categories whose definitions are missing are still included in the
     * configured slot list — they are rendered with the id path as the
     * header name — but their installed plugins have already been moved to
     * the trailing {@code [未知种类]} group by
     * {@link PluginTypeDegradationHandler#groupPluginsByType}, so the
     * configured header always shows {@code （0/插槽数）}.</p>
     *
     * @param slots          the gun's slot configuration (category id → count)
     * @param registryAccess the runtime registry view
     * @return a sorted list of {@link SlotEntry}
     */
    private static List<SlotEntry> collectSlotEntries(
            Map<ResourceLocation, Integer> slots, RegistryAccess registryAccess) {
        List<SlotEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> e : slots.entrySet()) {
            ResourceLocation typeId = e.getKey();
            Optional<PluginTypeDefinition> defOpt =
                    PluginTypeRegistry.getPluginType(registryAccess, typeId);
            entries.add(new SlotEntry(typeId, defOpt.orElse(null), e.getValue()));
        }
        entries.sort(PluginBarTooltipBuilder::compareSlots);
        return entries;
    }

    /**
     * Compares two slot entries for tooltip ordering.
     *
     * <p>Primary sort key: category {@code priority} descending. Secondary
     * sort key: category id ascending. Missing definitions use priority
     * {@code 0} (设计文档 line 1473).</p>
     *
     * @param a the first entry
     * @param b the second entry
     * @return a negative integer if {@code a} should appear before
     *         {@code b}
     */
    private static int compareSlots(SlotEntry a, SlotEntry b) {
        int pa = a.definition() != null ? a.definition().priority() : 0;
        int pb = b.definition() != null ? b.definition().priority() : 0;
        int byPriority = Integer.compare(pb, pa);
        if (byPriority != 0) {
            return byPriority;
        }
        return a.typeId().compareTo(b.typeId());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Builds the category header line {@code [类型名]（已安装数/插槽数）}.
     *
     * <p>The category name is coloured with its definition {@code color}
     * when present; the count suffix is grey (设计文档 line 1473).</p>
     *
     * @param entry          the slot entry
     * @param installedCount the number of plugins installed in this category
     * @return a {@link Component} header line
     */
    private static Component buildTypeHeader(SlotEntry entry, int installedCount) {
        String name = entry.definition() != null
                ? entry.definition().name().filter(s -> !s.isEmpty()).orElse(entry.typeId().getPath())
                : entry.typeId().getPath();
        MutableComponent nameComp = Component.literal("[" + name + "]");
        if (entry.definition() != null) {
            Optional<String> color = entry.definition().color();
            if (color.isPresent()) {
                nameComp = nameComp.withColor(parseHexColor(color.get()));
            }
        }
        return nameComp.append(Component.literal(
                "（" + installedCount + "/" + entry.slotCount() + "）")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Builds the tooltip line(s) for a single installed plugin.
     *
     * <p>A-04: when the plugin definition is missing the line degrades to a
     * grey {@code [失效插件] <path>} with no brief or description (设计文档
     * line 2356). Otherwise the default view shows
     * {@code ⚓插件名 - brief} (⚓ when locked) and the Shift view expands to
     * {@code ⚓插件名} followed by {@code |description} (or {@code |brief})
     * (设计文档 lines 1474, 1500-1502).</p>
     *
     * @param plugin         the installed plugin instance
     * @param registryAccess the runtime registry view
     * @param shift          whether Shift is held (expand description)
     * @return a list of {@link Component} lines (1 or 2 lines)
     */
    private static List<Component> buildPluginLines(
            PluginInstance plugin, RegistryAccess registryAccess, boolean shift) {
        List<Component> lines = new ArrayList<>();
        // A-04: degraded plugin — grey [失效插件] <path>, no brief/description.
        if (PluginDegradationHandler.isPluginDefinitionMissing(plugin, registryAccess)) {
            Component anchor = plugin.locked()
                    ? Component.literal("⚓") : Component.literal("");
            lines.add(Component.literal("  ")
                    .append(anchor)
                    .append(PluginDegradationHandler.getDegradedPluginName(plugin)));
            return lines;
        }

        Optional<PluginDefinition> defOpt =
                ModularShootAPI.getPluginDefinition(registryAccess, plugin.pluginId());
        if (defOpt.isEmpty()) {
            // Defensive: isPluginDefinitionMissing should have caught this.
            lines.add(Component.literal("  " + plugin.pluginId().getPath())
                    .withStyle(ChatFormatting.GRAY));
            return lines;
        }
        PluginDefinition def = defOpt.get();
        String name = def.name().filter(s -> !s.isEmpty()).orElse(plugin.pluginId().getPath());
        MutableComponent nameComp = Component.literal(name);
        Optional<String> color = def.color();
        if (color.isPresent()) {
            nameComp = nameComp.withColor(parseHexColor(color.get()));
        }

        Component anchor = plugin.locked()
                ? Component.literal("⚓") : Component.literal("");
        if (shift) {
            // Shift: 插件名 on first line, |description (or |brief) on second.
            lines.add(Component.literal("  ").append(anchor).append(nameComp));
            Optional<String> detail = def.description().filter(s -> !s.isEmpty())
                    .or(() -> def.brief().filter(s -> !s.isEmpty()));
            detail.ifPresent(d ->
                    lines.add(Component.literal("   |" + d).withStyle(ChatFormatting.GRAY)));
        } else {
            // Default: 插件名 - brief.
            MutableComponent line = Component.literal("  ").append(anchor).append(nameComp);
            Optional<String> brief = def.brief().filter(s -> !s.isEmpty());
            if (brief.isPresent()) {
                line = line.append(Component.literal(" - "))
                        .append(Component.literal(brief.get()).withStyle(ChatFormatting.GRAY));
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * Parses a hex colour string to an RGB integer.
     *
     * <p>Accepts both {@code "#FFAA00"} and {@code "FFAA00"} formats.
     * Falls back to white ({@code 0xFFFFFF}) on parse failure so a
     * malformed colour never crashes the tooltip.</p>
     *
     * @param hex the hex colour string
     * @return the RGB integer value
     */
    private static int parseHexColor(String hex) {
        try {
            String clean = hex.startsWith("#") ? hex.substring(1) : hex;
            return Integer.parseInt(clean, 16);
        } catch (NumberFormatException ex) {
            return 0xFFFFFF;
        }
    }

    // ------------------------------------------------------------------
    // Internal data carrier
    // ------------------------------------------------------------------

    /**
     * Immutable carrier for a configured slot entry: category id, definition
     * (nullable when missing), and slot count.
     *
     * @param typeId     the category id
     * @param definition the category definition, or {@code null} when missing
     * @param slotCount  the number of slots configured for this category
     */
    private record SlotEntry(
            ResourceLocation typeId,
            @Nullable PluginTypeDefinition definition,
            int slotCount
    ) {
    }
}
