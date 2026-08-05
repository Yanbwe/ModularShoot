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

/**
 * Aggregates the {@code extra_values} extension fields declared by a gun's
 * installed plugins (设计文档 §插件扩展注册字段).
 *
 * <p>The framework carries {@code extra_values} on plugin definitions purely
 * as namespaced numeric data (e.g. {@code {"raritycore:rarity": 5.0}}) and
 * never interprets their meaning. This service provides the <em>summation
 * semantics</em> integration mods need: for every installed plugin the value
 * of each key is accumulated into a single map, so a gun's total "rarity
 * budget" (or any other registered field) can be read at once and translated
 * into the integration mod's own systems (e.g. writing a rarity component).</p>
 *
 * <p><b>中文说明：</b>插件可以在 {@code extra_values} 中声明任意命名空间的
 * 数值字段（如稀有度值）。本服务按 key 对所有已安装插件求和，供集成模组在
 * {@code PostPluginInstallEvent}/{@code PostPluginUninstallEvent} 中读取并
 * 应用到自己的系统。聚合遵循与属性管线相同的降级口径：定义已失效（数据包
 * 被移除）的插件实例会被 {@link PluginDegradationHandler} 过滤，不参与求和。
 * 未声明过的 key 不会出现在结果 map 中，调用方用 {@code getOrDefault} 或
 * 便捷方法 {@link #get} 获得默认值 0。</p>
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
     * Sums the {@code extra_values} of every plugin installed on the given gun
     * stack by key.
     *
     * <p>Reads the installed-plugin list from the stack's {@code gun_data}
     * component; a stack without that component yields an empty map. Note
     * that the component is authoritative for the stack itself, so this
     * overload is correct for any gun stack (main hand, inventory, dropped)
     * on the server side.</p>
     *
     * @param gunStack       the gun item stack to aggregate; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view (from a loaded world);
     *                       must not be {@code null}
     * @return an immutable map of key &rarr; accumulated sum; empty when the
     *         stack carries no gun data or no valid instance declares any
     *         extra value
     */
    public static Map<ResourceLocation, Double> aggregate(ItemStack gunStack, RegistryAccess registryAccess) {
        GunData gunData = gunStack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Map.of();
        }
        return aggregate(gunData.installedPlugins(), registryAccess);
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
