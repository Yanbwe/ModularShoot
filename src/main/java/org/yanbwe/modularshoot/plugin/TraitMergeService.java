package org.yanbwe.modularshoot.plugin;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.RegistryKeyedCache;
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

    /**
     * Cached, merged trait results keyed weakly by the plugins
     * {@link Registry} instance and then by
     * {@code (gunDefinition, gunId, modifierVersion, ordered plugin ids)}.
     *
     * <p>See class Javadoc for why this cache can never go stale: the plugin
     * registry <em>instance</em> is the weak key, so {@code /reload} (which
     * swaps the instance) is a guaranteed cache miss, and
     * {@code modifierVersion} + the ordered plugin-id list invalidate on every
     * install/uninstall/lock change. The returned map is unmodifiable and thus
     * safe to share across callers.</p>
     */
    private static final RegistryKeyedCache<TraitCacheKey, Map<ResourceLocation, Boolean>> TRAIT_CACHE =
            new RegistryKeyedCache<>();

    /**
     * Immutable composite key identifying one merge result. Deliberately
     * derived from the result-determining inputs only — the ordered plugin ids
     * (duplicates preserved) rather than full {@link PluginInstance}s, whose
     * per-instance {@code instanceUuid}s would fragment the cache across
     * otherwise-identical guns.
     */
    private record TraitCacheKey(
            GunDefinition gunDefinition,
            ResourceLocation gunId,
            int modifierVersion,
            List<ResourceLocation> pluginIds) {
    }

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
        return computeTraits(gun, registryAccess, gunDefinition);
    }

    /**
     * Computes the final boolean trait map from an already-resolved
     * {@link GunDefinition} (设计文档 §射击路径单次解析与复用).
     *
     * <p>Identical merge semantics to {@link #computeTraits(ItemStack,
     * RegistryAccess)}, but the caller supplies the {@link GunDefinition}
     * resolved earlier in the shooting entry point so the {@code guns}
     * registry is <em>not</em> queried a second time. {@code null} gun data
     * or {@code null} definition degrades to an empty map, matching the
     * two-argument overload.</p>
     *
     * @param gun            the gun item stack to read {@link GunData} from
     * @param registryAccess the runtime registry view used to resolve plugin
     *                       definitions (never the gun definition)
     * @param gunDefinition  the gun definition already resolved by the caller;
     *                       {@code null} yields an empty map
     * @return an unmodifiable map of trait id → final boolean value
     */
    public static Map<ResourceLocation, Boolean> computeTraits(
            ItemStack gun, RegistryAccess registryAccess, @org.jetbrains.annotations.Nullable GunDefinition gunDefinition) {
        if (gunDefinition == null) {
            return Map.of();
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Map.of();
        }
        // 任务 1.3：相同 (GunData 内容, modifierVersion, Registry 实例) 的结果
        // 直接复用，不再重新解析每个插件定义 + 排序合并。缓存键以 plugins
        // Registry 实例为弱键（reload 换实例即失效），插件成员仅投影出有序
        // pluginId 列表（重复保留），配合 modifierVersion 覆盖安装/卸载/锁定。
        Registry<PluginDefinition> pluginsRegistry =
                registryAccess.registry(ModularShootRegistries.PLUGINS_KEY).orElse(null);
        if (pluginsRegistry != null) {
            TraitCacheKey key = new TraitCacheKey(
                    gunDefinition,
                    gunData.gunId(),
                    gunData.modifierVersion(),
                    gunData.installedPlugins().stream().map(PluginInstance::pluginId).toList());
            return TRAIT_CACHE.computeIfAbsent(pluginsRegistry, key,
                    () -> computeUncached(gunData, gunDefinition, registryAccess));
        }
        // 注册表缺失（主菜单 / EMPTY 视图）时按原语义降级：所有插件被视为失效，
        // 仅保留枪械固有 traits。
        return computeUncached(gunData, gunDefinition, registryAccess);
    }

    /**
     * Uncacheable core of {@link #computeTraits(ItemStack, RegistryAccess,
     * GunDefinition)}: resolves + sorts plugins and merges their traits with
     * the gun's inherent traits.
     *
     * @param gunData       the non-null gun data carrying the installed plugins
     * @param gunDefinition the non-null gun definition carrying the inherent traits
     * @param registryAccess the runtime registry view used to resolve plugins
     * @return an unmodifiable map of trait id → final boolean value
     */
    private static Map<ResourceLocation, Boolean> computeUncached(
            GunData gunData, GunDefinition gunDefinition, RegistryAccess registryAccess) {
        List<PluginDefinition> sortedPlugins = sortPluginsByPriority(gunData.installedPlugins(), registryAccess);
        Map<ResourceLocation, Boolean> merged = mergePluginTraits(sortedPlugins);
        merged.putAll(gunDefinition.traits());
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Resolves and sorts installed plugins by priority ascending, preserving
     * install order as the same-priority tiebreaker.
     *
     * <p>Degraded plugins (whose definition is missing from the registry) are
     * filtered out up-front by
     * {@link PluginDegradationHandler#filterValidPlugins} so their trait
     * overrides never participate in the merge (设计文档 §插件 pluginId 失效降级).
     * Each remaining {@link PluginInstance} is resolved to its
     * {@link PluginDefinition} via
     * {@link PluginRegistry#getPlugin(RegistryAccess, ResourceLocation)};
     * instances whose definition is missing are silently skipped. The
     * resulting stream is an ordered stream, so
     * {@link java.util.stream.Stream#sorted(Comparator) Stream.sorted} is
     * stable and equal-priority plugins retain their relative install
     * order (设计文档 line 668).</p>
     *
     * <p><b>Defensive double-filtering (S30).</b> The pipeline applies two
     * filters that, on the surface, appear to reject the same set of plugins:
     * first {@link PluginDegradationHandler#filterValidPlugins} drops
     * instances whose {@code pluginId} does not resolve, then the
     * {@code .filter(definition -> definition != null)} step drops any
     * {@code null} returned by the subsequent
     * {@link PluginRegistry#getPlugin(RegistryAccess, ResourceLocation)} call.
     * The second filter is intentionally retained as <em>defensive
     * programming</em>: it guards against the registry state changing between
     * the two lookups (e.g. a datapack reload racing this call), against
     * future changes to {@code filterValidPlugins}'s contract, and against
     * any {@link PluginRegistry} implementation that might return
     * {@code null} instead of {@code Optional.empty()}. The cost is a
     * redundant registry lookup per plugin, which is negligible for the
     * small installed-plugin lists this method operates on.</p>
     *
     * @param plugins        the installed plugin instances in install order
     * @param registryAccess the runtime registry view for plugin lookups
     * @return an immutable list of plugin definitions sorted by priority
     *         ascending, stable on install order
     */
    private static List<PluginDefinition> sortPluginsByPriority(List<PluginInstance> plugins, RegistryAccess registryAccess) {
        return PluginDegradationHandler.filterValidPlugins(plugins, registryAccess).stream()
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
