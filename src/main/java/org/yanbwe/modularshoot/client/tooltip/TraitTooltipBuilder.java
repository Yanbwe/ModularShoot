package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.plugin.TraitMergeService;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.Trait;

/**
 * Builds the trait-bar section of a gun's tooltip (设计文档 §系统九,
 * lines 1450-1459, 1485-1490, 1558-1576).
 *
 * <p>Iterates the {@code modularshoot:traits} registry, reads each trait's
 * final boolean value from {@link TraitMergeService} (which merges gun
 * inherent traits with installed-plugin traits), filters by visibility
 * rules, sorts by {@code priority} descending then by id ascending, and
 * renders each as a coloured {@link Component} line.</p>
 *
 * <h2>Display layers</h2>
 * <ul>
 *   <li><b>Default (no modifier key)</b> — shows only traits whose value is
 *       {@code true}, plus any trait with {@code force_show = true}
 *       (设计文档 line 1488).</li>
 *   <li><b>Alt held</b> — same filtering as default, but each trait line is
 *       followed by a grey {@code |description} (or {@code |brief} when no
 *       description is set) detail line (设计文档 lines 1566-1568).</li>
 * </ul>
 *
 * <h2>Rendering rules</h2>
 * <ul>
 *   <li>The symbol {@code [♦]} (green) marks a {@code true} trait;
 *       {@code [×]} (red) marks a {@code false} trait (设计文档 line 1486).</li>
 *   <li>The trait name uses the metadata {@code color}; however, when
 *       {@code force_show = true} and the value is {@code false}, the name
 *       is rendered grey to signal "this trait exists but is not active"
 *       (设计文档 line 1489).</li>
 *   <li>When the trait definition carries no {@code name}, the trait id
 *       path is used as a fallback.</li>
 *   <li>Display text ({@code name}, {@code description}, {@code brief})
 *       supports the {@code lang:} translation-key prefix via
 *       {@link TooltipUtils#resolveText} (设计文档 §本地化).</li>
 * </ul>
 *
 * @see TooltipBuilder
 * @see TraitMergeService
 */
public final class TraitTooltipBuilder {
    private TraitTooltipBuilder() {
    }

    /**
     * Builds the trait-bar tooltip lines for a gun stack.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute the final trait map via
     *       {@link TraitMergeService#computeTraits}.</li>
     *   <li>Iterate the {@code traits} registry; for each trait read its
     *       current value (falling back to the definition default).</li>
     *   <li>Filter: keep traits whose value is {@code true} or whose
     *       {@code force_show} is {@code true} (设计文档 line 1488).</li>
     *   <li>Sort by {@code priority} descending, then by trait id
     *       ascending.</li>
     *   <li>Render a grey {@code "特性:"} header followed by one or two
     *       lines per trait (two when Alt is held and a description/brief
     *       exists).</li>
     * </ol>
     * </p>
     *
     * @param gunStack       the gun item stack to read traits from
     * @param viewingPlayer  the player viewing the tooltip (unused, reserved
     *                       for future per-player filtering); may be
     *                       {@code null}
     * @param registryAccess the runtime registry view
     * @return an ordered list of trait-bar components (including the
     *         {@code "特性:"} header); empty when no trait is displayable
     *         or the registry is absent
     */
    public static List<Component> buildTraitBar(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        Optional<Registry<Trait>> registryOpt =
                registryAccess.registry(ModularShootRegistries.TRAITS_KEY);
        if (registryOpt.isEmpty()) {
            return List.of();
        }
        Registry<Trait> registry = registryOpt.get();

        Map<ResourceLocation, Boolean> traitValues =
                TraitMergeService.computeTraits(gunStack, registryAccess);

        List<TraitEntry> entries = collectEntries(registry, traitValues);
        if (entries.isEmpty()) {
            return List.of();
        }
        entries.sort(TraitTooltipBuilder::compareEntries);

        boolean alt = Screen.hasAltDown();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("特性:").withStyle(ChatFormatting.GRAY));
        for (TraitEntry entry : entries) {
            lines.addAll(buildLines(entry, alt));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Collection & filtering
    // ------------------------------------------------------------------

    /**
     * Collects visible trait entries from the registry.
     *
     * <p>For each {@link Trait} definition, reads the current value from the
     * merged trait map (falling back to the definition default when the
     * trait is not present in the map). Keeps only traits whose value is
     * {@code true} or whose {@code force_show} is {@code true}
     * (设计文档 line 1488).</p>
     *
     * @param registry    the traits registry
     * @param traitValues the merged trait map from {@link TraitMergeService}
     * @return a list of visible {@link TraitEntry}
     */
    private static List<TraitEntry> collectEntries(
            Registry<Trait> registry,
            Map<ResourceLocation, Boolean> traitValues) {
        List<TraitEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceKey<Trait>, Trait> regEntry : registry.entrySet()) {
            ResourceLocation traitId = regEntry.getKey().location();
            Trait def = regEntry.getValue();
            boolean value = traitValues.getOrDefault(traitId, def.defaultValue());

            // Filter: show true traits, or force_show traits regardless of value.
            if (!value && !def.forceShow()) {
                continue;
            }
            entries.add(new TraitEntry(traitId, def, value));
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    /**
     * Compares two trait entries for tooltip ordering.
     *
     * <p>Primary sort key: {@code priority} descending. Secondary sort key:
     * trait id ascending (设计文档 line 1490).</p>
     *
     * @param a the first entry
     * @param b the second entry
     * @return a negative integer if {@code a} should appear before {@code b}
     */
    private static int compareEntries(TraitEntry a, TraitEntry b) {
        int byPriority = Integer.compare(b.def().priority(), a.def().priority());
        if (byPriority != 0) {
            return byPriority;
        }
        return a.id().compareTo(b.id());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Builds the tooltip line(s) for a single trait entry.
     *
     * <p>Always produces one main line: {@code "  [symbol]name"}. When Alt
     * is held and a description or brief exists, a second grey detail line
     * {@code "   |detail"} is appended (设计文档 lines 1486-1489,
     * 1566-1568).</p>
     *
     * @param entry the trait entry to render
     * @param alt   whether Alt is held (expand description)
     * @return a list of 1 or 2 {@link Component} lines
     */
    private static List<Component> buildLines(TraitEntry entry, boolean alt) {
        List<Component> lines = new ArrayList<>(2);
        Trait def = entry.def();
        boolean value = entry.value();

        // Symbol: ♦ green for true, × red for false (设计文档 line 1486).
        Component symbol = value
                ? Component.literal("[♦]").withStyle(ChatFormatting.GREEN)
                : Component.literal("[×]").withStyle(ChatFormatting.RED);

        // Name: use definition name or fall back to id path.
        String nameText = def.name().filter(s -> !s.isEmpty()).orElse(entry.id().getPath());
        MutableComponent nameComp = TooltipUtils.resolveText(nameText);

        // Colour: grey when force_show && !value; otherwise use definition colour.
        if (def.forceShow() && !value) {
            nameComp = nameComp.withStyle(ChatFormatting.GRAY);
        } else {
            Optional<String> color = def.color();
            if (color.isPresent()) {
                nameComp = nameComp.withColor(TooltipUtils.parseHexColor(color.get()));
            }
        }

        lines.add(Component.empty()
                .append(Component.literal("  "))
                .append(symbol)
                .append(nameComp));

        // Alt: append description (or brief) detail line.
        if (alt) {
            Optional<String> detail = resolveDetail(def);
            detail.ifPresent(d -> lines.add(Component.empty()
                    .append(Component.literal("   |").withStyle(ChatFormatting.GRAY))
                    .append(TooltipUtils.resolveText(d).withStyle(ChatFormatting.GRAY))));
        }
        return lines;
    }

    /**
     * Resolves the detail text for the Alt-expanded view.
     *
     * <p>Returns the description when non-empty; otherwise falls back to the
     * brief when non-empty; otherwise empty (设计文档 line 1567).</p>
     *
     * @param def the trait definition
     * @return the detail text, or empty when neither is set
     */
    private static Optional<String> resolveDetail(Trait def) {
        String desc = def.description();
        if (desc != null && !desc.isEmpty()) {
            return Optional.of(desc);
        }
        return def.brief().filter(s -> !s.isEmpty());
    }

    // ------------------------------------------------------------------
    // Internal data carrier
    // ------------------------------------------------------------------

    /**
     * Immutable carrier for a collected trait entry's id, definition, and
     * current boolean value.
     *
     * @param id    the trait definition id (registry key)
     * @param def   the trait definition (display metadata, default value)
     * @param value the current merged boolean value
     */
    private record TraitEntry(
            ResourceLocation id,
            Trait def,
            boolean value
    ) {
    }
}
