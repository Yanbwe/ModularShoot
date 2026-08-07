package org.yanbwe.modularshoot.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.GunItemBindingRegistry;
import org.yanbwe.modularshoot.registry.binding.PluginItemBinding;
import org.yanbwe.modularshoot.registry.binding.PluginItemBindingRegistry;

/**
 * Post-reload validator for the item binding tables
 * ({@code modularshoot:gun_items} / {@code modularshoot:plugin_items})
 * (设计规格 物品绑定系统 §3.2).
 *
 * <p>A binding entry references two external ids: the bound item id (a
 * vanilla {@code ITEM} registry entry) and the binding target (a
 * {@code guns} or {@code plugins} table entry). A typo in either id
 * registered fine before — the entry was accepted but the binding silently
 * resolved to nothing at runtime. This class turns every dangling reference
 * into an explicit {@code WARN} via
 * {@link DatapackErrorHandler#logReferenceWarning(ResourceLocation, String)},
 * following the exact conventions of {@link CrossReferenceValidator}: the
 * entry stays registered regardless of the outcome (degradation is deferred,
 * 降级模式).</p>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>Pure data style mirroring {@link CrossReferenceValidator#validateGuns}:
 *       the caller passes the already-loaded entries map.</li>
 *   <li>Empty entries maps return immediately (注册表缺失时静默 — a missing
 *       binding table is a legitimate state, not a cascade of warnings).</li>
 *   <li>{@code findDuplicateItemKeys} is a package-private pure function so
 *       the duplicate-binding rule ("同一物品 ID 出现多个绑定 → WARN +
 *       字典序最小键胜出") is unit-testable without a
 *       {@link RegistryAccess}.</li>
 *   <li>Java-API bindings (设计规格 物品绑定系统 §3.2) take priority over
 *       datapack bindings for the same item id (注册表服务语义): a datapack
 *       entry shadowed by a Java-API binding is flagged with a
 *       {@code WARN}.</li>
 * </ul>
 *
 * @see DatapackErrorHandler
 * @see CrossReferenceValidator
 * @see GunItemBinding
 * @see PluginItemBinding
 */
public final class ItemBindingValidator {

    private ItemBindingValidator() {
    }

    /**
     * Validates every {@code gun_items} binding entry (设计规格 物品绑定系统
     * §3.2).
     *
     * <p>Checks performed (all emit {@code WARN} via
     * {@link DatapackErrorHandler#logReferenceWarning}):</p>
     * <ul>
     *   <li>{@code item} exists in the vanilla {@code ITEM} registry
     *       (绑定物品不存在时条目保留但运行时绑定不生效)</li>
     *   <li>{@code gun} exists in the {@code guns} table (枪械定义缺失时
     *       自然表现为"无法射击" + 现有缺失定义提示)</li>
     *   <li>a bound item id appears in at most one entry — the entry with the
     *       lexicographically smallest key wins, every other entry for the
     *       same item id warns (同一物品 ID 重复绑定，字典序最小键胜出)</li>
     *   <li>the bound item id is not already bound via the Java API —
     *       when it is, the Java-API binding wins at runtime and this entry
     *       is shadowed (与 Java API 绑定冲突，Java API 优先，设计规格
     *       §3.2)</li>
     * </ul>
     *
     * @param access  the reloaded registry access
     * @param entries the loaded binding-table key to {@link GunItemBinding}
     *                entries
     */
    public static void validateGunBindings(
            RegistryAccess access, Map<ResourceLocation, GunItemBinding> entries) {
        if (entries.isEmpty()) {
            return;  // 注册表缺失时静默
        }
        Set<ResourceLocation> gunKeys = registryKeys(access, ModularShootRegistries.GUNS_KEY);
        Set<ResourceLocation> javaApiItemIds = GunItemBindingRegistry.getJavaApiBindings()
                .values().stream().map(GunItemBinding::itemId).collect(Collectors.toSet());
        for (Map.Entry<ResourceLocation, GunItemBinding> entry : entries.entrySet()) {
            ResourceLocation key = entry.getKey();
            GunItemBinding binding = entry.getValue();
            if (!BuiltInRegistries.ITEM.containsKey(binding.itemId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "绑定物品不存在: " + binding.itemId());
            }
            if (!gunKeys.contains(binding.gunId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "绑定枪械不存在: " + binding.gunId());
            }
            if (javaApiItemIds.contains(binding.itemId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "与 Java API 绑定冲突，Java API 优先: " + binding.itemId());
            }
        }
        warnDuplicateBindings(entries, GunItemBinding::itemId);
    }

    /**
     * Validates every {@code plugin_items} binding entry — fully symmetric to
     * {@link #validateGunBindings} against the {@code plugins} table
     * (设计规格 物品绑定系统 §3.2); the Java-API conflict check likewise
     * mirrors the gun variant.
     *
     * @param access  the reloaded registry access
     * @param entries the loaded binding-table key to {@link PluginItemBinding}
     *                entries
     */
    public static void validatePluginBindings(
            RegistryAccess access, Map<ResourceLocation, PluginItemBinding> entries) {
        if (entries.isEmpty()) {
            return;  // 注册表缺失时静默
        }
        Set<ResourceLocation> pluginKeys =
                registryKeys(access, ModularShootRegistries.PLUGINS_KEY);
        Set<ResourceLocation> javaApiItemIds = PluginItemBindingRegistry.getJavaApiBindings()
                .values().stream().map(PluginItemBinding::itemId).collect(Collectors.toSet());
        for (Map.Entry<ResourceLocation, PluginItemBinding> entry : entries.entrySet()) {
            ResourceLocation key = entry.getKey();
            PluginItemBinding binding = entry.getValue();
            if (!BuiltInRegistries.ITEM.containsKey(binding.itemId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "绑定物品不存在: " + binding.itemId());
            }
            if (!pluginKeys.contains(binding.pluginId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "绑定插件不存在: " + binding.pluginId());
            }
            if (javaApiItemIds.contains(binding.itemId())) {
                DatapackErrorHandler.logReferenceWarning(key,
                        "与 Java API 绑定冲突，Java API 优先: " + binding.itemId());
            }
        }
        warnDuplicateBindings(entries, PluginItemBinding::itemId);
    }

    /**
     * Returns the keys of all duplicate binding entries, excluding the
     * lexicographically smallest key of each duplicate group.
     *
     * <p>Pure function (no {@link RegistryAccess}) so the duplicate-binding
     * rule is unit-testable. Keys sharing one {@code itemId} form a group;
     * when a group holds more than one entry, the smallest key (by
     * {@link ResourceLocation} natural order) is kept and every other key is
     * returned. The result follows the entries map's iteration order.</p>
     *
     * <p>Empty {@code entries} yields an empty list.</p>
     *
     * @param entries          the binding-table key to entry map (item id
     *                         extracted from the values)
     * @param itemIdExtractor  extracts the bound item id from an entry
     * @param <T>              the binding entry type
     * @return the non-smallest keys of every duplicate group, in the entries
     *         map's iteration order; empty when no item id is bound more than
     *         once
     */
    static <T> List<ResourceLocation> findDuplicateItemKeys(
            Map<ResourceLocation, ? extends T> entries,
            Function<T, ResourceLocation> itemIdExtractor) {
        if (entries.isEmpty()) {
            return List.of();
        }
        // 按 itemId 分组，组内保持 entries 遍历顺序
        Map<ResourceLocation, List<ResourceLocation>> keysByItem = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ? extends T> entry : entries.entrySet()) {
            ResourceLocation itemId = itemIdExtractor.apply(entry.getValue());
            keysByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(entry.getKey());
        }
        List<ResourceLocation> duplicates = new ArrayList<>();
        for (List<ResourceLocation> keys : keysByItem.values()) {
            if (keys.size() > 1) {
                ResourceLocation smallest =
                        keys.stream().min(ResourceLocation::compareTo).orElseThrow();
                for (ResourceLocation key : keys) {
                    if (!key.equals(smallest)) {
                        duplicates.add(key);
                    }
                }
            }
        }
        return duplicates;
    }

    /**
     * Emits a {@code WARN} per duplicate binding entry (保留字典序最小 key,
     * 其余 WARN).
     *
     * @param entries          the binding-table key to entry map
     * @param itemIdExtractor  extracts the bound item id from an entry
     * @param <T>              the binding entry type
     */
    private static <T> void warnDuplicateBindings(
            Map<ResourceLocation, ? extends T> entries,
            Function<T, ResourceLocation> itemIdExtractor) {
        for (ResourceLocation duplicateKey : findDuplicateItemKeys(entries, itemIdExtractor)) {
            ResourceLocation itemId = itemIdExtractor.apply(entries.get(duplicateKey));
            DatapackErrorHandler.logReferenceWarning(duplicateKey,
                    "同一物品 ID 重复绑定: " + itemId
                            + "（字典序最小条目 key 胜出，本条目绑定被忽略）");
        }
    }

    /**
     * Collects the key set of a framework registry; empty when the registry
     * is absent.
     *
     * @param access the registry access
     * @param key    the registry key
     * @param <T>    the registry value type
     * @return the registry's id set, or {@link Set#of()} when absent
     */
    private static <T> Set<ResourceLocation> registryKeys(
            RegistryAccess access, ResourceKey<Registry<T>> key) {
        return access.registry(key).map(Registry::keySet).orElse(Set.of());
    }
}
