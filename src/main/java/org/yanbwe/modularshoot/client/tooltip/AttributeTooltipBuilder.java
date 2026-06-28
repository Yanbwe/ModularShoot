package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.degradation.AttributeBindsDegradationHandler;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;

/**
 * Builds the attribute-bar section of a gun's tooltip (设计文档 §系统九,
 * lines 1419-1425, 1473-1484, 1537-1556).
 *
 * <p>Iterates the {@code modularshoot:attribute_meta} registry, reads each
 * attribute's current value from the gun's {@code ATTRIBUTE_MODIFIERS}
 * component, filters by visibility rules, sorts by {@code priority}
 * descending then by id ascending, and renders each as a coloured
 * {@link Component} line.</p>
 *
 * <h2>Display layers</h2>
 * <ul>
 *   <li><b>Default (no modifier key)</b> — shows only attributes whose value
 *       differs from the default, plus any attribute with
 *       {@code force_show = true} (设计文档 line 1482).</li>
 *   <li><b>Ctrl held</b> — shows every registered attribute without
 *       filtering (设计文档 line 1546).</li>
 * </ul>
 *
 * <h2>Value computation</h2>
 * <p>Framework attributes have a vanilla base of {@code 0}; the final value
 * is computed by simulating {@code AttributeInstance.getValue()} on the
 * modifiers stored in the {@code ATTRIBUTE_MODIFIERS} component:
 * <ol>
 *   <li>{@code sum_add = Σ ADD_VALUE amounts}</li>
 *   <li>{@code mul_base = Σ ADD_MULTIPLIED_BASE amounts}</li>
 *   <li>{@code mul_total = Σ ADD_MULTIPLIED_TOTAL amounts}</li>
 *   <li>{@code final = sum_add × (1 + mul_base) × (1 + mul_total)}</li>
 * </ol>
 * </p>
 *
 * <h2>Degradation</h2>
 * <p>Attributes whose {@code binds} target is unregistered in the vanilla
 * {@code ATTRIBUTE} registry are silently skipped via
 * {@link AttributeBindsDegradationHandler#isAttributeRegistered} (设计文档
 * §属性元数据 binds 失效降级).</p>
 *
 * @see TooltipBuilder
 * @see AttributeBindsDegradationHandler
 */
public final class AttributeTooltipBuilder {
    private AttributeTooltipBuilder() {
    }

    /**
     * Builds the attribute-bar tooltip lines for a gun stack.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Read the {@code ATTRIBUTE_MODIFIERS} component and compute the
     *       final value for every attribute.</li>
     *   <li>Iterate the {@code attribute_meta} registry; skip entries whose
     *       {@code binds} target is unregistered (degradation).</li>
     *   <li>Filter: default layer hides attributes whose value equals the
     *       default unless {@code force_show}; Ctrl shows all.</li>
     *   <li>Sort by {@code priority} descending, then by attribute id
     *       ascending.</li>
     *   <li>Render a grey {@code "属性:"} header followed by one line per
     *       attribute.</li>
     * </ol>
     * </p>
     *
     * @param gunStack       the gun item stack to read modifiers from
     * @param viewingPlayer  the player viewing the tooltip (unused, reserved
     *                       for future per-player filtering); may be
     *                       {@code null}
     * @param registryAccess the runtime registry view
     * @return an ordered list of attribute-bar components (including the
     *         {@code "属性:"} header); empty when no attribute is
     *         displayable or the registry is absent
     */
    public static List<Component> buildAttributeBar(
            ItemStack gunStack,
            @Nullable Player viewingPlayer,
            RegistryAccess registryAccess) {
        Optional<Registry<AttributeMeta>> registryOpt =
                registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY);
        if (registryOpt.isEmpty()) {
            return List.of();
        }
        Registry<AttributeMeta> registry = registryOpt.get();

        Map<ResourceLocation, Double> computed = computeAllAttributes(gunStack);
        boolean ctrl = Screen.hasControlDown();
        List<AttributeEntry> entries = collectEntries(registry, computed, ctrl);
        if (entries.isEmpty()) {
            return List.of();
        }
        entries.sort(AttributeTooltipBuilder::compareEntries);

        List<Component> lines = new ArrayList<>(entries.size() + 1);
        lines.add(Component.literal("属性:").withStyle(ChatFormatting.GRAY));
        for (AttributeEntry entry : entries) {
            lines.add(buildLine(entry));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Value computation
    // ------------------------------------------------------------------

    /**
     * Computes the final value for every attribute present in the gun's
     * {@code ATTRIBUTE_MODIFIERS} component.
     *
     * <p>Simulates {@code AttributeInstance.getValue()} by grouping
     * modifiers by attribute id, summing per-operation amounts, and applying
     * the vanilla stacking formula:
     * {@code sum_add × (1 + mul_base) × (1 + mul_total)}.</p>
     *
     * @param gunStack the gun stack to read modifiers from
     * @return a map of attribute id → final value; empty when the component
     *         is absent
     */
    private static Map<ResourceLocation, Double> computeAllAttributes(ItemStack gunStack) {
        ItemAttributeModifiers modifiers = gunStack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return Map.of();
        }

        // Per-attribute operation sums: [0]=ADD_VALUE, [1]=MUL_BASE, [2]=MUL_TOTAL
        Map<ResourceLocation, double[]> sums = new HashMap<>();
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            Optional<ResourceLocation> attrIdOpt =
                    entry.attribute().unwrapKey().map(ResourceKey::location);
            if (attrIdOpt.isEmpty()) {
                continue;
            }
            double[] vals = sums.computeIfAbsent(attrIdOpt.get(), k -> new double[3]);
            switch (entry.modifier().operation()) {
                case ADD_VALUE -> vals[0] += entry.modifier().amount();
                case ADD_MULTIPLIED_BASE -> vals[1] += entry.modifier().amount();
                case ADD_MULTIPLIED_TOTAL -> vals[2] += entry.modifier().amount();
            }
        }

        Map<ResourceLocation, Double> result = new HashMap<>();
        for (Map.Entry<ResourceLocation, double[]> e : sums.entrySet()) {
            double[] vals = e.getValue();
            double finalValue = vals[0] * (1.0 + vals[1]) * (1.0 + vals[2]);
            result.put(e.getKey(), finalValue);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Collection & filtering
    // ------------------------------------------------------------------

    /**
     * Collects visible attribute entries from the registry.
     *
     * <p>For each {@link AttributeMeta} entry:
     * <ol>
     *   <li>Skip when the {@code binds} target is unregistered
     *       (degradation).</li>
     *   <li>Read the current value from the computed map (default
     *       {@code 0.0} when no modifier targets this attribute).</li>
     *   <li>Filter: in the default layer, hide entries whose value equals
     *       the default unless {@code force_show}; in the Ctrl layer, keep
     *       all.</li>
     * </ol>
     * </p>
     *
     * @param registry  the attribute metadata registry
     * @param computed  the pre-computed attribute values
     * @param ctrl      whether Ctrl is held (show all attributes)
     * @return a list of visible {@link AttributeEntry}
     */
    private static List<AttributeEntry> collectEntries(
            Registry<AttributeMeta> registry,
            Map<ResourceLocation, Double> computed,
            boolean ctrl) {
        List<AttributeEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceKey<AttributeMeta>, AttributeMeta> regEntry : registry.entrySet()) {
            ResourceLocation attrId = regEntry.getKey().location();
            AttributeMeta meta = regEntry.getValue();

            // Degradation: skip attributes whose binds target is unregistered.
            if (!AttributeBindsDegradationHandler.isAttributeRegistered(meta.binds())) {
                continue;
            }

            double currentValue = computed.getOrDefault(meta.binds(), 0.0);

            // Filter: default layer hides default-valued attributes unless force_show.
            boolean show = ctrl || meta.forceShow() || currentValue != meta.defaultValue();
            if (!show) {
                continue;
            }
            entries.add(new AttributeEntry(attrId, meta, currentValue));
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Sorting
    // ------------------------------------------------------------------

    /**
     * Compares two attribute entries for tooltip ordering.
     *
     * <p>Primary sort key: {@code priority} descending. Secondary sort key:
     * attribute id ascending (设计文档 line 1482).</p>
     *
     * @param a the first entry
     * @param b the second entry
     * @return a negative integer if {@code a} should appear before {@code b}
     */
    private static int compareEntries(AttributeEntry a, AttributeEntry b) {
        int byPriority = Integer.compare(b.meta().priority(), a.meta().priority());
        if (byPriority != 0) {
            return byPriority;
        }
        return a.id().compareTo(b.id());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Builds a single tooltip line for an attribute entry.
     *
     * <p>Format: {@code "  <name>: <value>"} where the name is coloured with
     * the metadata {@code color} (via the vanilla attribute's
     * {@code descriptionId} translation key) and the value is grey
     * (设计文档 lines 1479-1483).</p>
     *
     * @param entry the attribute entry to render
     * @return a {@link Component} line
     */
    private static Component buildLine(AttributeEntry entry) {
        AttributeMeta meta = entry.meta();
        int nameColor = meta.color().map(TooltipUtils::parseHexColor).orElse(0xFFFFFF);
        String valueText = TooltipUtils.formatValue(entry.value());

        MutableComponent nameComp = resolveAttributeName(meta.binds()).withColor(nameColor);
        return Component.empty()
                .append(Component.literal("  "))
                .append(nameComp)
                .append(Component.literal(": "))
                .append(Component.literal(valueText).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Resolves the display-name component for an attribute by looking up
     * its vanilla {@code descriptionId} translation key.
     *
     * <p>Falls back to the binds id path when the attribute holder cannot
     * be resolved (should not happen after the degradation guard, but is
     * defensive).</p>
     *
     * @param bindsId the vanilla attribute id
     * @return a {@link MutableComponent} for the attribute name
     */
    private static MutableComponent resolveAttributeName(ResourceLocation bindsId) {
        Optional<Holder.Reference<Attribute>> holderOpt =
                BuiltInRegistries.ATTRIBUTE.getHolder(bindsId);
        if (holderOpt.isPresent()) {
            String descriptionId = holderOpt.get().value().getDescriptionId();
            return Component.translatable(descriptionId);
        }
        return Component.literal(bindsId.getPath());
    }

    // ------------------------------------------------------------------
    // Internal data carrier
    // ------------------------------------------------------------------

    /**
     * Immutable carrier for a collected attribute entry's id, metadata, and
     * current value.
     *
     * @param id    the attribute metadata registry key (logical attribute id)
     * @param meta  the attribute metadata (display info, default value)
     * @param value the current computed value
     */
    private record AttributeEntry(
            ResourceLocation id,
            AttributeMeta meta,
            double value
    ) {
    }
}
