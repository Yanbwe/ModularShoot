package org.yanbwe.modularshoot.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Effective slot computation for the {@code adds_slots} extension
 * (设计规格 §adds_slots 槽位扩展 §4).
 *
 * <p>The effective slot configuration of a gun is <strong>not</strong> the
 * static {@code gunDef.slots()} map alone: every installed plugin's
 * {@code adds_slots} contribution extends it. The effective <em>key set</em>
 * is the union of the gun's declared slot types and every installed plugin's
 * declared types (a plugin can create a slot type the gun never declared),
 * and the effective <em>capacity</em> of a type is the gun's base value plus
 * the sum of every installed plugin's contribution (defaulting to 0, so
 * negative values occupy slots).</p>
 *
 * <p>Degradation contract: a plugin whose definition is missing from the
 * {@code modularshoot:plugins} registry contributes nothing — its keys are
 * not merged and its values count as zero, matching the plugin pipeline's
 * degradation semantics. When the gun definition itself is missing, the
 * effective slot map is empty and the removal check conservatively reports
 * {@code false} (no overflow can be proven).</p>
 *
 * <p>All methods are static; the two core algorithms
 * ({@link #aggregateSlots} and {@link #overflowAfterRemoval}) are pure
 * functions so the semantics are unit-testable without a
 * {@link RegistryAccess}.</p>
 */
public final class EffectiveSlotService {

    private EffectiveSlotService() {
    }

    /**
     * 纯函数：聚合枪械定义 slots 与一组插件定义的 adds_slots。
     *
     * <p>键集 = 枪械键 ∪ 插件键；容量 = 枪械值 + Σ 插件值（未声明键按 0）。
     * 返回不可变 map。降级插件（定义缺失）由调用方在解析定义时跳过。</p>
     *
     * @param gunDef     the gun definition carrying the base slot configuration
     * @param pluginDefs the installed plugins' definitions (degraded entries
     *                   already filtered out by the caller)
     * @return an immutable map of slot type → effective capacity
     */
    public static Map<ResourceLocation, Integer> aggregateSlots(
            GunDefinition gunDef, List<PluginDefinition> pluginDefs) {
        Map<ResourceLocation, Integer> result = new HashMap<>(gunDef.slots());
        for (PluginDefinition def : pluginDefs) {
            for (Map.Entry<ResourceLocation, Integer> e : def.addsSlots().entrySet()) {
                result.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 纯函数：给定卸载后的剩余插件（remaining）及其定义（remainingDefs 按索引
     * 一一对应，定义缺失用 {@code null} 占位、贡献按 0），判定是否任意槽位类型
     * 超编。
     *
     * @param gunDef         the gun definition carrying the base slot
     *                       configuration
     * @param remaining      the plugin instances that stay installed
     * @param remainingDefs  the definitions of {@code remaining}, indexed
     *                       one-to-one; {@code null} entries are degraded
     *                       (contribute nothing)
     * @return {@code true} when any installed type exceeds its effective
     *         capacity
     */
    public static boolean overflowAfterRemoval(
            GunDefinition gunDef,
            List<PluginInstance> remaining,
            List<@Nullable PluginDefinition> remainingDefs) {
        Map<ResourceLocation, Integer> after = aggregateSlots(gunDef,
                remainingDefs.stream().filter(Objects::nonNull).toList());
        // Single-pass counting: tally each type once, then compare each
        // against its effective capacity (O(n) instead of O(n²)).
        Map<ResourceLocation, Long> counts = new HashMap<>();
        for (PluginInstance p : remaining) {
            counts.merge(p.installedTypeId(), 1L, Long::sum);
        }
        for (Map.Entry<ResourceLocation, Long> e : counts.entrySet()) {
            if (e.getValue() > after.getOrDefault(e.getKey(), 0)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 运行时便捷重载：从 gun stack 解析枪械定义与全部已装插件定义后聚合有效
     * 槽位。
     *
     * @param gun    the gun item stack to inspect
     * @param access the runtime registry view (from a loaded world)
     * @return an immutable map of slot type → effective capacity; empty when
     *         the stack carries no {@code gun_data} or the gun definition is
     *         missing
     */
    public static Map<ResourceLocation, Integer> effectiveSlots(
            ItemStack gun, RegistryAccess access) {
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return Map.of();
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(access, gunData.gunId());
        if (gunDef.isEmpty()) {
            return Map.of();
        }
        return aggregateSlots(gunDef.get(), resolveDefs(gunData.installedPlugins(), access));
    }

    /**
     * 运行时重载：模拟卸载 {@code target} 后是否任意槽位类型超编。
     *
     * <p>枪械定义缺失时保守放行（{@code false}）——容量无法证明，不阻塞卸载。
     * 已装插件定义缺失（降级）按贡献 0 参与判定。</p>
     *
     * @param gunData  the gun's current data component
     * @param target   the plugin instance about to be removed
     * @param access   the runtime registry view
     * @return {@code true} when removing {@code target} would leave some slot
     *         type over capacity
     */
    public static boolean removalCausesOverflow(
            GunData gunData, PluginInstance target, RegistryAccess access) {
        Optional<GunDefinition> gunDef = GunRegistry.getGun(access, gunData.gunId());
        if (gunDef.isEmpty()) {
            return false;
        }
        List<PluginInstance> remaining = new ArrayList<>();
        List<PluginDefinition> remainingDefs = new ArrayList<>();
        for (PluginInstance p : gunData.installedPlugins()) {
            if (p.instanceUuid().equals(target.instanceUuid())) {
                continue;
            }
            remaining.add(p);
            remainingDefs.add(PluginRegistry.getPlugin(access, p.pluginId()).orElse(null));
        }
        return overflowAfterRemoval(gunDef.get(), remaining, remainingDefs);
    }

    /**
     * Resolves every installed plugin's definition, skipping (omitting)
     * degraded entries whose definition is missing from the registry.
     *
     * <p>Package-visible so the same-package matching service can reuse the
     * resolution without duplicating the degradation rule.</p>
     *
     * @param installed the installed plugin instances
     * @param access    the runtime registry view
     * @return the resolvable definitions in {@code installed}'s order
     */
    static List<PluginDefinition> resolveDefs(
            List<PluginInstance> installed, RegistryAccess access) {
        List<PluginDefinition> defs = new ArrayList<>(installed.size());
        for (PluginInstance p : installed) {
            PluginRegistry.getPlugin(access, p.pluginId()).ifPresent(defs::add);
        }
        return defs;
    }
}
