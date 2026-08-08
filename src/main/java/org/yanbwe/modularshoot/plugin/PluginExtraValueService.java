package org.yanbwe.modularshoot.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Aggregates the {@code extra_values} extension fields declared by a gun
 * definition and its installed plugins (设计文档 §插件扩展注册字段).
 *
 * <p>The framework carries {@code extra_values} on gun and plugin definitions
 * purely as namespaced numeric data (e.g. {@code {"raritycore:rarity": 5.0}})
 * and never interprets their meaning. This service provides the <em>summation
 * semantics</em> integration mods need: the gun definition's own base values
 * enter the sum unchanged, and for every installed plugin the value of each
 * key is accumulated into a single map, so a gun's total "rarity budget" (or
 * any other registered field) can be read at once and translated into the
 * integration mod's own systems (e.g. writing a rarity component).</p>
 *
 * <p><b>中文说明：</b>枪械定义可在 {@code extra_values} 中声明基础值，插件声明
 * 累加值，均为任意命名空间的数值字段（如稀有度值）。本服务按 key 汇总"枪械
 * 基础值 + 已安装插件累计"，供集成模组在 {@code PostPluginInstallEvent}/
 * {@code PostPluginUninstallEvent} 中读取并应用到自己的系统。聚合遵循与属性
 * 管线相同的降级口径：定义已失效（数据包被移除）的插件实例会被
 * {@link PluginDegradationHandler} 过滤，不参与求和；枪械定义失效时其基础值
 * 同样不参与。未声明过的 key 不会出现在结果 map 中，调用方用
 * {@code getOrDefault} 或便捷方法 {@link #get} 获得默认值 0。</p>
 *
 * <p>The {@code RegistryAccess}-backed overloads require a loaded world (the
 * {@code modularshoot:plugins} registry is datapack-driven and empty on the
 * main menu); callers must supply
 * {@code player.registryAccess()} or equivalent.</p>
 */
public final class PluginExtraValueService {

    private PluginExtraValueService() {
    }

    /**
     * Sums the {@code extraValues} of the given definitions by key.
     *
     * <p>Pure function — no registry access, safe to call anywhere. Keys
     * never declared by any definition stay absent from the result.</p>
     *
     * @param definitions the plugin definitions to aggregate; must not be
     *                    {@code null}
     * @return an immutable map of key &rarr; accumulated sum; empty when no
     *         definition declares any extra value
     */
    public static Map<ResourceLocation, Double> aggregateDefinitions(List<PluginDefinition> definitions) {
        Map<ResourceLocation, Double> sums = new HashMap<>();
        for (PluginDefinition definition : definitions) {
            for (Map.Entry<ResourceLocation, Double> entry : definition.extraValues().entrySet()) {
                sums.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        return Map.copyOf(sums);
    }

    /**
     * Sums the given base values together with the {@code extra_values} of the
     * given plugin definitions by key.
     *
     * <p>Pure function — no registry access, safe to call anywhere. The base
     * values (e.g. a gun definition's own {@code extra_values}) enter the sum
     * unchanged; plugin values accumulate per key on top. Keys never declared
     * anywhere stay absent from the result.</p>
     *
     * @param baseValues        the base values (e.g. the gun definition's
     *                          {@code extra_values}); must not be {@code null}
     * @param pluginDefinitions the plugin definitions to aggregate; must not be
     *                          {@code null}
     * @return an immutable map of key &rarr; base + accumulated sum; empty
     *         when both inputs are empty
     */
    public static Map<ResourceLocation, Double> aggregateWithBase(
            Map<ResourceLocation, Double> baseValues,
            List<PluginDefinition> pluginDefinitions) {
        Map<ResourceLocation, Double> sums = new HashMap<>(baseValues);
        for (PluginDefinition definition : pluginDefinitions) {
            for (Map.Entry<ResourceLocation, Double> entry : definition.extraValues().entrySet()) {
                sums.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        return Map.copyOf(sums);
    }

    /**
     * Sums the {@code extra_values} of the given installed plugin instances by
     * key, filtering degraded instances first.
     *
     * <p>Instances whose definition is missing from the
     * {@code modularshoot:plugins} registry are filtered via
     * {@link PluginDegradationHandler#filterValidPlugins} — the same
     * degradation contract as the attribute-modifier pipeline — and therefore
     * contribute no sums. Install order is irrelevant to the result (addition
     * is commutative).</p>
     *
     * @param instances      the installed plugin instances; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view (from a loaded world);
     *                       must not be {@code null}
     * @return an immutable map of key &rarr; accumulated sum; empty when no
     *         valid instance declares any extra value
     */
    public static Map<ResourceLocation, Double> aggregate(
            List<PluginInstance> instances, RegistryAccess registryAccess) {
        List<PluginDefinition> definitions = new ArrayList<>();
        for (PluginInstance instance : PluginDegradationHandler.filterValidPlugins(instances, registryAccess)) {
            PluginRegistry.getPlugin(registryAccess, instance.pluginId()).ifPresent(definitions::add);
        }
        return aggregateDefinitions(definitions);
    }

    /**
     * Sums the gun definition's base {@code extra_values} together with the
     * {@code extra_values} of every plugin installed on the given gun stack,
     * by key.
     *
     * <p>Reads the installed-plugin list from the stack's {@code gun_data}
     * component; a stack without that component yields an empty map. The base
     * values come from the gun definition resolved via {@code gun_data.gunId}
     * (skipped when the definition is missing). Note that the component is
     * authoritative for the stack itself, so this overload is correct for any
     * gun stack (main hand, inventory, dropped) on the server side.</p>
     *
     * @param gunStack       the gun item stack to aggregate; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view (from a loaded world);
     *                       must not be {@code null}
     * @return an immutable map of key &rarr; base + accumulated sum; empty
     *         when the stack carries no gun data or neither the gun definition
     *         nor any valid instance declares an extra value
     */
    public static Map<ResourceLocation, Double> aggregate(ItemStack gunStack, RegistryAccess registryAccess) {
        GunData gunData = gunStack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Map.of();
        }
        List<PluginDefinition> definitions = new ArrayList<>();
        for (PluginInstance instance : PluginDegradationHandler.filterValidPlugins(gunData.installedPlugins(), registryAccess)) {
            PluginRegistry.getPlugin(registryAccess, instance.pluginId()).ifPresent(definitions::add);
        }
        // 枪械定义基础值并入总和；定义缺失（降级）时基础值不参与，
        // 与插件降级过滤同一口径。
        Map<ResourceLocation, Double> baseValues = GunRegistry.getGun(registryAccess, gunData.gunId())
                .map(GunDefinition::extraValues)
                .orElseGet(Map::of);
        return aggregateWithBase(baseValues, definitions);
    }

    /**
     * Convenience single-key lookup: the accumulated sum of {@code key} over
     * every valid plugin installed on the given gun, or {@code 0.0} when no
     * plugin declares that key (or the gun data is absent).
     *
     * @param gunStack       the gun item stack to aggregate; must not be
     *                       {@code null}
     * @param key            the extra-value key to look up; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view (from a loaded world);
     *                       must not be {@code null}
     * @return the accumulated value of {@code key}, or {@code 0.0} when
     *         undeclared
     */
    public static double get(ItemStack gunStack, ResourceLocation key, RegistryAccess registryAccess) {
        return aggregate(gunStack, registryAccess).getOrDefault(key, 0.0);
    }
}
