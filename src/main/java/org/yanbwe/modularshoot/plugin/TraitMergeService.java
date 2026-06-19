package org.yanbwe.modularshoot.plugin;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Pure-function service that merges boolean traits from a gun's inherent
 * definition and its installed plugins into the final trait map consumed by
 * the shooting engine (设计文档 §布尔特性合并规则, lines 654-704).
 *
 * <h2>Merge rules</h2>
 * <ol>
 *   <li><b>Plugin sorting</b> — all installed plugins are ordered by
 *       {@code priority} ascending. Plugins sharing the same priority keep
 *       their install order (the order of {@code installedPlugins} in
 *       {@link GunData}), which is guaranteed by a stable sort on an ordered
 *       stream.</li>
 *   <li><b>Plugin merge</b> — starting from the lowest priority, each
 *       plugin's traits overwrite the accumulated result, so a
 *       higher-priority plugin — or, at equal priority, a later-installed
 *       one — wins over earlier contributors.</li>
 *   <li><b>Gun override</b> — after every plugin has been merged, the gun's
 *       inherent traits overwrite the result. Any trait the gun declares
 *       always wins over every plugin; traits the gun does <em>not</em>
 *       declare retain the plugin-merged value.</li>
 * </ol>
 *
 * <h2>Examples</h2>
 * <ul>
 *   <li>Gun declares {@code some_trait=false}, plugin A(100)=true,
 *       plugin B(200)=false → plugin merge = false (B overwrites A) →
 *       gun override = false.</li>
 *   <li>Gun declares {@code some_trait=true}, plugin A(100, first)=false,
 *       plugin B(100, last)=false → plugin merge = false (later install
 *       overwrites) → gun override = true.</li>
 *   <li>Gun does not declare {@code some_trait}, plugin A(100)=true,
 *       plugin B(200)=false → plugin merge = false → final = false.</li>
 * </ul>
 *
 * <p>This is a <b>pure function</b>: the same gun definition plus the same
 * installed-plugin list always produce the same output, with no side
 * effects. The returned map is unmodifiable.</p>
 *
 * <p>All methods are static utility methods; the class is not
 * instantiable.</p>
 */
public final class TraitMergeService {

    private TraitMergeService() {
    }

    /**
     * Computes the final boolean trait map for a gun stack by merging the
     * inherent gun traits with the traits of every installed plugin.
     *
     * <p>Plugins are sorted by priority ascending (stable on install order),
     * merged low-to-high so higher priority overwrites lower, then the gun's
     * inherent traits overwrite the result. See the class Javadoc for the
     * full rules and examples.</p>
     *
     * @param gun            the gun item stack to read {@link GunData} from
     * @param registryAccess the runtime registry view used to look up the gun
     *                       and plugin definitions
     * @return an unmodifiable map of trait id → final boolean value; an empty
     *         map when the stack has no gun data or the gun definition is
     *         missing from the registry
     */
    public static Map<ResourceLocation, Boolean> computeTraits(ItemStack gun, RegistryAccess registryAccess) {
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Map.of();
        }
        GunDefinition gunDefinition = GunRegistry.getGun(registryAccess, gunData.gunId()).orElse(null);
        if (gunDefinition == null) {
            return Map.of();
        }
        List<PluginDefinition> sortedPlugins = sortPluginsByPriority(gunData.installedPlugins(), registryAccess);
        Map<ResourceLocation, Boolean> merged = mergePluginTraits(sortedPlugins);
        merged.putAll(gunDefinition.traits());
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Resolves and sorts installed plugins by priority ascending, preserving
     * install order as the same-priority tiebreaker.
     *
     * <p>Each {@link PluginInstance} is resolved to its {@link PluginDefinition}
     * via {@link PluginRegistry#getPlugin(RegistryAccess, ResourceLocation)};
     * instances whose definition is missing from the registry are silently
     * skipped. The resulting stream is an ordered stream, so
     * {@link java.util.stream.Stream#sorted(Comparator) Stream.sorted} is
     * stable and equal-priority plugins retain their relative install
     * order (设计文档 line 668).</p>
     *
     * @param plugins        the installed plugin instances in install order
     * @param registryAccess the runtime registry view for plugin lookups
     * @return an immutable list of plugin definitions sorted by priority
     *         ascending, stable on install order
     */
    private static List<PluginDefinition> sortPluginsByPriority(List<PluginInstance> plugins, RegistryAccess registryAccess) {
        return plugins.stream()
                .map(instance -> PluginRegistry.getPlugin(registryAccess, instance.pluginId()).orElse(null))
                .filter(definition -> definition != null)
                .sorted(Comparator.comparingInt(PluginDefinition::priority))
                .toList();
    }

    /**
     * Merges the traits of a priority-sorted plugin list into a single map,
     * low priority first so each subsequent plugin overwrites earlier ones.
     *
     * @param sortedPlugins plugin definitions already sorted by priority
     *                      ascending (and install-order-stable)
     * @return a mutable {@link LinkedHashMap} accumulating the merged traits,
     *         ready for the gun-override step
     */
    private static Map<ResourceLocation, Boolean> mergePluginTraits(List<PluginDefinition> sortedPlugins) {
        Map<ResourceLocation, Boolean> merged = new LinkedHashMap<>();
        for (PluginDefinition plugin : sortedPlugins) {
            merged.putAll(plugin.traits());
        }
        return merged;
    }
}
