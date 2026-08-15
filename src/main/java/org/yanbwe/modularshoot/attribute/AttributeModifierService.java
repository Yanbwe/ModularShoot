package org.yanbwe.modularshoot.attribute;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.AttributeBindsDegradationHandler;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginModifier;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.RegistryKeyedCache;
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.util.GunRecognition;

/**
 * Computes the {@code ATTRIBUTE_MODIFIERS} component for a gun item stack.
 *
 * <p>The framework describes its logical attributes (e.g.
 * {@code modularshoot:hit_damage}) through hot-reloadable {@link AttributeMeta}
 * entries in the {@code modularshoot:attribute_meta} datapack registry, whose
 * {@code binds} field points at an already-registered vanilla
 * {@link Attribute} id; a gun's base values are applied through
 * {@code ADD_VALUE} modifiers written into the item's
 * {@link DataComponents#ATTRIBUTE_MODIFIERS} component. Vanilla's
 * {@code LivingEntity.detectEquipmentUpdates} then mounts these modifiers onto
 * any player holding the gun in the main hand, so the framework never manages
 * player-side modifiers itself (设计文档 §系统五).
 *
 * <p>Gun base modifiers are <em>meta 驱动</em>: the service iterates every
 * entry of the {@code attribute_meta} registry and mounts one
 * {@code ADD_VALUE} modifier onto the attribute resolved from the entry's
 * {@code binds} field via {@link AttributeResolver#resolveBoundHolder}. A
 * datapack can therefore rebind a logical attribute to <em>any</em>
 * registered vanilla attribute (e.g. {@code modularshoot:hit_damage} →
 * {@code minecraft:attack_damage}) without code changes; entries whose
 * {@code binds} target is not registered are skipped (降级契约, 设计文档
 * §属性元数据 binds 失效降级). Iterating the registry itself also means a
 * datapack-added {@code attribute_meta} entry automatically makes the gun
 * {@code stats} key of that logical attribute supported &mdash; no preset
 * list to update.
 *
 * <p>The service merges two sources of modifiers into one component:
 * <ul>
 *   <li><b>Gun base values</b> &mdash; one {@code ADD_VALUE} modifier per
 *       {@code attribute_meta} entry, all sharing the stable id
 *       {@link #GUN_BASE_MODIFIER_ID} ({@code modularshoot:gun_base}).</li>
 *   <li><b>Plugin modifiers</b> &mdash; the modifiers declared by each
 *       installed plugin ({@link PluginDefinition#modifiers()}), mapped from
 *       the plugin's {@link PluginModifier.Operation} to the corresponding
 *       vanilla {@link AttributeModifier.Operation}. Every modifier from one
 *       plugin instance shares a single id derived stably from the plugin's
 *       {@code pluginId} plus its install-order occurrence index (设计文档
 *       §修饰符 ID 稳定性) — a <em>cross-instance stable</em> id, not the
 *       per-gun {@code instanceUuid}, so identical plugin configurations on
 *       different guns share one cached result. Because all modifiers
 *       from one instance share a single id, vanilla's
 *       {@code AttributeInstance.addModifier} would throw if two modifiers
 *       targeted the same attribute; {@link #addPluginModifiers} therefore
 *       deduplicates by attribute, keeping only the first modifier per
 *       attribute per instance.</li>
 * </ul>
 *
 * <p>The combined set is written with {@code slot = MAINHAND} and
 * {@code showInTooltip = false}; the framework renders its own tooltip.
 * Stacking order (ADD_VALUE &rarr; ADD_MULTIPLIED_BASE &rarr;
 * ADD_MULTIPLIED_TOTAL) is executed by the vanilla attribute system, so the
 * framework only needs to write the modifiers (设计文档 §修饰符叠加规则).
 *
 * <p>Every gun base modifier shares the stable id {@code modularshoot:gun_base}
 * so vanilla can correctly match and replace them on refresh. Different
 * attributes may share this id because vanilla keys modifier identity by the
 * (attribute, id) pair, and each attribute here is distinct. Each plugin's
 * modifiers use the stable id derived from the plugin's {@code pluginId} plus
 * its occurrence index (not the per-gun {@code instanceUuid}), so identical
 * plugin configurations on different gun copies share the same modifier ids
 * and thus the same cached component (设计文档 §修饰符 ID 稳定性). When a
 * plugin definition declares multiple modifiers targeting the same attribute,
 * only the first is mounted; see {@link #addPluginModifiers} for the
 * deduplication rationale.
 */
public final class AttributeModifierService {

    /** Stable modifier id for gun base values (设计文档 §修饰符 ID 稳定性). */
    public static final ResourceLocation GUN_BASE_MODIFIER_ID =
            ResourceLocation.parse("modularshoot:gun_base");

    /**
     * Immutable cache key for one computed modifier set (阶段 5 / 任务 5.2).
     *
     * <p>The result of {@link #computeAllModifiers} depends only on (a) the
     * {@link GunDefinition} actually passed in (its {@code stats} directly
     * determine the gun base values), (b) the {@code modifierVersion},
     * (c) the <em>ordered pluginId list</em> (duplicates preserved, not the
     * full {@link PluginInstance}s) and (d) the {@code attribute_meta}
     * registry instance. Deliberately <em>not</em> including the per-instance
     * {@code instanceUuid}s lets two players holding otherwise-identical guns
     * (same gun definition, same plugin list, same version) share one cached
     * result — the full-table recomputation is avoided across players and
     * across gun copies. The {@code attribute_meta} registry instance is part
     * of the key (identity-based equality) so a {@code /reload} that swaps in a
     * new instance is a guaranteed miss even in the degenerate case where the
     * {@code plugins} token is reused.</p>
     *
     * @param metaRegistry    the {@code attribute_meta} registry instance that
     *                        supplied the base-modifier table
     * @param gunDef          the gun definition actually passed to
     *                        {@link #computeAllModifiers}; its {@code stats}
     *                        drive the base values, so it is part of the key
     * @param gunId           the gun definition id
     * @param modifierVersion the anti-cheat version counter; incremented on
     *                        install/uninstall/lock so those events invalidate
     *                        the cache naturally
     * @param pluginIds       the installed plugin ids in install order
     *                        (duplicates preserved); the result depends on
     *                        which plugin definitions are present and in what
     *                        order they are mounted
     */
    private record ModifierCacheKey(
            Registry<AttributeMeta> metaRegistry,
            GunDefinition gunDef,
            ResourceLocation gunId,
            int modifierVersion,
            List<ResourceLocation> pluginIds) {
    }

    /**
     * Weak-keyed cache of computed {@code ATTRIBUTE_MODIFIERS} (阶段 5 /
     * 任务 5.2, reload 在线玩家刷新缓存).
     *
     * <p>Reuses the {@link RegistryKeyedCache} pattern already used by
     * {@link org.yanbwe.modularshoot.plugin.TraitMergeService} and
     * {@link org.yanbwe.modularshoot.plugin.PluginExtraValueService}: the outer
     * weak key is the {@code modularshoot:plugins} {@link Registry}
     * <em>instance</em>, so a {@code /reload} that swaps in a new instance is
     * a guaranteed cache miss and never serves stale results from the old one.
     * The {@code guns} registry is covered through the {@code gunDef} field in
     * the key (callers resolve it from the current guns registry each call, so
     * a reloaded gun definition yields a new key), and the
     * {@code attribute_meta} registry is likewise carried in the key. Within
     * one registry instance, two players holding identical guns (same
     * definition, same ordered plugin ids, same {@code modifierVersion}) share
     * one computed result; a plugin install/uninstall/lock increments
     * {@code modifierVersion} and thus misses the cache too.</p>
     */
    private static final RegistryKeyedCache<ModifierCacheKey, ItemAttributeModifiers> MODIFIER_CACHE =
            new RegistryKeyedCache<>();

    private AttributeModifierService() {
    }

    /**
     * Computes the {@link ItemAttributeModifiers} for a gun definition,
     * applying <em>only</em> the gun base values.
     *
     * <p>Iterates every entry of the {@code modularshoot:attribute_meta}
     * datapack registry and mounts one {@code ADD_VALUE} modifier per entry,
     * resolved as follows:
     * <ol>
     *   <li>the mount target is the vanilla {@link Attribute} resolved from
     *       the entry's {@code binds} field
     *       ({@link AttributeResolver#resolveBoundHolder});</li>
     *   <li>the value is the gun's declared {@code stats} value for the
     *       entry's logical id, falling back to
     *       {@link AttributeMeta#defaultValue()} when undeclared;</li>
     *   <li>entries whose {@code binds} target is not registered in the
     *       vanilla {@code ATTRIBUTE} registry are skipped (降级契约).</li>
     * </ol>
     * Each modifier uses the stable {@link #GUN_BASE_MODIFIER_ID}, bound to
     * {@link EquipmentSlotGroup#MAINHAND} with {@code showInTooltip = false}
     * (the framework renders its own tooltip, 屏蔽原版属性修饰符提示行).
     *
     * <p>This method is retained for backward compatibility with M1 callers
     * that create a gun with an empty plugin list. Use
     * {@link #computeAllModifiers} when installed plugins must also be merged.
     *
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view (for {@code attribute_meta})
     * @return an immutable {@link ItemAttributeModifiers} with one main-hand
     *         entry per {@code attribute_meta} entry and tooltip hidden
     */
    public static ItemAttributeModifiers computeGunModifiers(GunDefinition gunDef, RegistryAccess registryAccess) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        addBaseModifiers(builder, gunDef, registryAccess);
        return builder.build().withTooltip(false);
    }

    /**
     * Computes the {@link ItemAttributeModifiers} for a gun, merging both the
     * gun base values and the modifiers declared by every installed plugin.
     *
     * <p>Base values are added first (one {@code ADD_VALUE} per
     * {@code attribute_meta} entry, id {@link #GUN_BASE_MODIFIER_ID}); plugin
     * modifiers are added afterwards, each keyed by a stable id derived from
     * the plugin's {@code pluginId} and its occurrence index (cross-instance
     * shareable, see {@link #pluginModifierId}).
     * Modifiers targeting an attribute that is not registered in the vanilla
     * {@code ATTRIBUTE} registry are silently skipped. Plugins whose
     * definition can no longer be found in the {@code modularshoot:plugins}
     * registry are likewise skipped.
     *
     * @param gunDef         the gun definition supplying declared stats
     * @param gunData        the per-gun data carrying the installed plugin list
     * @param registryAccess the runtime registry view (for {@code attribute_meta}
     *                       and {@code modularshoot:plugins})
     * @return an immutable {@link ItemAttributeModifiers} with main-hand
     *         entries for base values and plugin modifiers, tooltip hidden
     */
    public static ItemAttributeModifiers computeAllModifiers(
            GunDefinition gunDef, GunData gunData, RegistryAccess registryAccess) {
        Registry<AttributeMeta> metaRegistry =
                registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY).orElse(null);
        Registry<PluginDefinition> pluginsRegistry =
                registryAccess.registry(ModularShootRegistries.PLUGINS_KEY).orElse(null);
        if (metaRegistry == null || pluginsRegistry == null) {
            // 任一决定输入对应的注册表缺失 → 走降级语义直接计算（无缓存），
            // 避免 null 键在多 RegistryAccess 间串扰（降级语义不变）。
            return computeAllModifiersUncached(gunDef, gunData, registryAccess);
        }
        ModifierCacheKey key = new ModifierCacheKey(
                metaRegistry,
                gunDef,
                gunData.gunId(),
                gunData.modifierVersion(),
                gunData.installedPlugins().stream().map(PluginInstance::pluginId).toList());
        return MODIFIER_CACHE.computeIfAbsent(
                pluginsRegistry,
                key,
                () -> computeAllModifiersUncached(gunDef, gunData, registryAccess));
    }

    /**
     * Uncached form of {@link #computeAllModifiers}: merges gun base values and
     * every installed plugin's modifiers into one immutable
     * {@link ItemAttributeModifiers} with tooltip hidden.
     */
    private static ItemAttributeModifiers computeAllModifiersUncached(
            GunDefinition gunDef, GunData gunData, RegistryAccess registryAccess) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        addBaseModifiers(builder, gunDef, registryAccess);
        addPluginModifiers(builder, gunData, registryAccess);
        return builder.build().withTooltip(false);
    }

    /**
     * Computes and writes the {@code ATTRIBUTE_MODIFIERS} component onto a gun
     * stack, applying <em>only</em> the gun base values (no plugin modifiers).
     *
     * <p>Used at gun creation time, when the plugin list is empty, and by any
     * caller that only needs base values. After plugins are installed or
     * removed, use {@link #refreshModifiers} instead, which merges plugin
     * modifiers via {@link #computeAllModifiers} (设计文档 §组件刷新时机).
     *
     * @param gunStack       the gun item stack to update (mutated)
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view
     */
    public static void applyModifiers(ItemStack gunStack, GunDefinition gunDef, RegistryAccess registryAccess) {
        gunStack.set(DataComponents.ATTRIBUTE_MODIFIERS, computeGunModifiers(gunDef, registryAccess));
    }

    /**
     * Refreshes the {@code ATTRIBUTE_MODIFIERS} component from the stack's own
     * {@link GunData}, merging gun base values with all installed plugin
     * modifiers.
     *
     * <p>Reads the {@code gun_id} from the stack, looks up the current
     * {@link GunDefinition} and re-applies the full modifier set (base values
     * plus every installed plugin's modifiers) via
     * {@link #computeAllModifiers}. The component is overwritten with
     * {@link ItemStack#set}, which clears all old modifiers before writing the
     * new ones, so stale entries from removed plugins never linger
     * (设计文档 §修饰符 ID 稳定性).
     *
     * <p>When the gun definition can no longer be found (e.g. the datapack was
     * removed), the component is reset to {@link ItemAttributeModifiers#EMPTY}
     * so stale modifiers are cleared rather than left dangling.
     *
     * @param gunStack       the gun item stack to refresh (mutated)
     * @param registryAccess the runtime registry view
     */
    public static void refreshModifiers(ItemStack gunStack, RegistryAccess registryAccess) {
        GunData gunData = gunStack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(registryAccess, gunData.gunId());
        if (gunDef.isPresent()) {
            gunStack.set(DataComponents.ATTRIBUTE_MODIFIERS,
                    computeAllModifiers(gunDef.get(), gunData, registryAccess));
        } else {
            gunStack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        }
    }

    /**
     * Refreshes the {@code ATTRIBUTE_MODIFIERS} component on every gun stack
     * in a player's main inventory ({@code inventory}) and offhand slot,
     * silently skipping non-gun stacks.
     *
     * <p>This is the <em>single shared</em> inventory-refresh implementation
     * (阶段 5 / 任务 5.2, 合并刷新路径): both the {@code /reload} online-player
     * refresh ({@code ReloadBehaviorHandler}) and the player-login lazy
     * refresh ({@code GunSyncService}) delegate here, so the two paths behave
     * identically rather than maintaining two copies of the scan. The 36-slot
     * main inventory and the offhand slot are scanned; armor slots are skipped
     * since a gun cannot be equipped there (设计文档 §惰性刷新路径 K2).</p>
     *
     * @param inventory       the player's main inventory slots
     *                        ({@code ServerPlayer#getInventory().items}); may
     *                        contain non-gun stacks, which are passed over
     * @param offhand         the player's offhand slot item
     * @param registryAccess  the runtime registry view
     * @return the number of gun stacks whose modifiers were refreshed
     */
    public static int refreshGunModifiersInInventory(
            Iterable<ItemStack> inventory, ItemStack offhand, RegistryAccess registryAccess) {
        int count = 0;
        for (ItemStack stack : inventory) {
            count += refreshIfGun(stack, registryAccess);
        }
        count += refreshIfGun(offhand, registryAccess);
        return count;
    }

    /**
     * Refreshes a single stack's modifiers when it is a gun, returning {@code 1}
     * when refreshed and {@code 0} when it is not a gun.
     */
    private static int refreshIfGun(ItemStack stack, RegistryAccess registryAccess) {
        // 阶段 6.1 待迁移：本方法通过 ModularShootAPI.isGun 判定枪械。此处暂借
        // 公共 API 判定，6.1 会将 isGun 下沉到内部工具类后移除该依赖（不阻塞）。
        if (GunRecognition.isGun(stack, registryAccess)) {
            refreshModifiers(stack, registryAccess);
            return 1;
        }
        return 0;
    }

    /**
     * Resolves the gun base value for a logical attribute.
     *
     * <p>Declared gun {@code stats} take precedence; the metadata entry's
     * {@link AttributeMeta#defaultValue()} is the fallback. The entry is
     * always present when called from {@link #addBaseModifiers} (the loop
     * iterates the registry itself), so no last-resort fallback is needed.
     *
     * @param logicalId the logical attribute id to resolve
     * @param gunDef    the gun definition supplying declared stats
     * @param meta      the {@code attribute_meta} entry for the logical id
     * @return the base value to feed into the {@code ADD_VALUE} modifier
     */
    private static double resolveBaseValue(
            ResourceLocation logicalId, GunDefinition gunDef, AttributeMeta meta) {
        Double declared = gunDef.stats().get(logicalId);
        if (declared != null) {
            return declared;
        }
        return meta.defaultValue();
    }

    /**
     * Adds the gun base value modifiers for every {@code attribute_meta}
     * entry to a builder (meta 驱动).
     *
     * <p>Iterates the {@code modularshoot:attribute_meta} datapack registry;
     * each entry produces one {@code ADD_VALUE} modifier mounted onto the
     * vanilla {@link Attribute} resolved from its {@code binds} field via
     * {@link AttributeResolver#resolveBoundHolder}, with the stable
     * {@link #GUN_BASE_MODIFIER_ID}, bound to
     * {@link EquipmentSlotGroup#MAINHAND}. The value is the gun's declared
     * {@code stats} for the entry's logical id, falling back to
     * {@link AttributeMeta#defaultValue()} when undeclared. Extracted from
     * {@link #computeGunModifiers} so {@link #computeAllModifiers} can reuse
     * the same logic before appending plugin modifiers.</p>
     *
     * <p><strong>Degradation contract.</strong> When the
     * {@code attribute_meta} registry is absent no modifiers are added
     * (注册表缺失 → 无基础修饰符). Entries whose {@code binds} target is not
     * registered in {@link BuiltInRegistries#ATTRIBUTE} are silently skipped
     * &mdash; no modifier is mounted and no exception is thrown. This covers
     * the binds-degradation contract (设计文档 §属性元数据 binds 失效降级):
     * when a third-party attribute body is uninstalled, its metadata entry
     * remains but the modifier is not mounted. The check is delegated to
     * {@link AttributeBindsDegradationHandler#isAttributeRegistered} so the
     * predicate lives in a single place.</p>
     *
     * @param builder        the builder to append entries to
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view (for {@code attribute_meta})
     */
    private static void addBaseModifiers(
            ItemAttributeModifiers.Builder builder, GunDefinition gunDef, RegistryAccess registryAccess) {
        Registry<AttributeMeta> metaRegistry =
                registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY).orElse(null);
        if (metaRegistry == null) {
            return;  // 注册表缺失 → 无基础修饰符（降级）
        }
        for (Map.Entry<ResourceKey<AttributeMeta>, AttributeMeta> entry : metaRegistry.entrySet()) {
            ResourceLocation logicalId = entry.getKey().location();
            AttributeMeta meta = entry.getValue();
            if (!AttributeBindsDegradationHandler.isAttributeRegistered(meta.binds())) {
                continue;  // binds 目标未注册 → 跳过（降级契约）
            }
            Holder<Attribute> holder = AttributeResolver.resolveBoundHolder(meta);
            if (holder == null) {
                continue;  // 防御：上面已检查，正常不会发生
            }
            double value = resolveBaseValue(logicalId, gunDef, meta);
            AttributeModifier modifier = new AttributeModifier(
                    GUN_BASE_MODIFIER_ID, value, AttributeModifier.Operation.ADD_VALUE);
            builder.add(holder, modifier, EquipmentSlotGroup.MAINHAND);
        }
    }

    /**
     * Adds the attribute modifiers declared by every installed plugin to a
     * builder.
     *
     * <p>Iterates the gun's {@link GunData#installedPlugins()} list, looks up
     * each plugin's {@link PluginDefinition} in the
     * {@code modularshoot:plugins} registry, and appends one
     * {@link AttributeModifier} per declared {@link PluginModifier}. All
     * modifiers from one plugin instance share a single id derived stably from
     * the plugin's {@code pluginId} and its install-order occurrence index
     * (设计文档 §修饰符 ID 稳定性). This id is <em>cross-instance
     * shareable</em>: two guns carrying the same ordered plugin list produce
     * identical modifier ids, which is what lets
     * {@link #computeAllModifiers}' cache be shared across players and gun
     * copies. The {@code occurrenceIndex} is taken against the <em>full</em>
     * installed-plugin list (before degradation filtering), so it stays stable
     * under the same plugin list and the cache key (which also captures all
     * installed plugin ids) remains correct even when a plugin is degraded.
     * Plugins whose definition is missing and modifiers whose target attribute
     * is not registered are silently skipped.</p>
     *
     * <p><strong>Duplicate-attribute guard (W3 fix).</strong> Because all
     * modifiers from one plugin instance share a single id, vanilla's
     * {@code AttributeInstance.addModifier} would throw
     * {@code IllegalArgumentException} if two modifiers targeted the same
     * attribute. This method tracks the attributes already seen for each
     * plugin instance and skips subsequent modifiers targeting a duplicate
     * attribute, logging a {@code WARN} so the issue is traceable. The
     * {@code PluginDatapackLoader} also emits a load-time warning for the
     * same condition, but the runtime guard is required because the
     * registry is frozen by the time that warning is emitted and cannot
     * remove the duplicate entries.</p>
     *
     * <p>The binds-degradation contract is enforced by
     * {@link #resolveAttributeHolder}, which calls
     * {@link BuiltInRegistries#ATTRIBUTE#getHolder(ResourceLocation)} and
     * returns {@code Optional.empty()} for unregistered attributes. This is
     * equivalent to the check in
     * {@link AttributeBindsDegradationHandler#isAttributeRegistered} &mdash;
     * both consult the same vanilla registry &mdash; so plugin modifiers
     * targeting a binds-failed attribute are not mounted (设计文档 §属性元数据
     * binds 失效降级).</p>
     *
     * @param builder        the builder to append entries to
     * @param gunData        the per-gun data carrying the installed plugin list
     * @param registryAccess the runtime registry view (for
     *                       {@code modularshoot:plugins})
     */
    private static void addPluginModifiers(
            ItemAttributeModifiers.Builder builder, GunData gunData, RegistryAccess registryAccess) {
        List<PluginInstance> installed = gunData.installedPlugins();
        // Keep the degradation-filtered set for O(1) membership, but derive the
        // occurrence index from the FULL installed list so the modifier id is
        // stable for the cache key (which also contains every installed plugin
        // id, degraded or not).
        Set<PluginInstance> valid = new HashSet<>(
                PluginDegradationHandler.filterValidPlugins(installed, registryAccess));
        for (int occurrenceIndex = 0; occurrenceIndex < installed.size(); occurrenceIndex++) {
            PluginInstance instance = installed.get(occurrenceIndex);
            if (!valid.contains(instance)) {
                continue;
            }
            int index = occurrenceIndex;
            PluginRegistry.getPlugin(registryAccess, instance.pluginId()).ifPresent(pluginDef ->
                    addSinglePluginModifiers(builder, instance, index, pluginDef));
        }
    }

    /**
     * Adds the attribute modifiers declared by a single installed plugin
     * instance to a builder, skipping duplicate attribute targets.
     *
     * <p>Extracted from {@link #addPluginModifiers} so the per-instance loop
     * body stays under 50 lines. All modifiers from this instance share one
     * id derived stably from {@code instance.pluginId()} and its
     * {@code occurrenceIndex} in the installed list — a cross-instance stable
     * id (not the per-gun {@code instanceUuid}), so identical plugin
     * configurations across guns share the same cached modifiers. When the
     * plugin definition declares multiple modifiers targeting the same
     * attribute, only the first is mounted; subsequent duplicates are skipped
     * with a {@code WARN} log to prevent vanilla's
     * {@code AttributeInstance.addModifier} from throwing
     * {@code IllegalArgumentException} (W3 fix).</p>
     *
     * @param builder         the builder to append entries to
     * @param instance        the installed plugin instance
     * @param occurrenceIndex the index of this instance in the full installed
     *                        plugin list (drives the stable id)
     * @param pluginDef       the plugin definition supplying declared modifiers
     */
    private static void addSinglePluginModifiers(
            ItemAttributeModifiers.Builder builder,
            PluginInstance instance,
            int occurrenceIndex,
            PluginDefinition pluginDef) {
        ResourceLocation modifierId = pluginModifierId(instance.pluginId(), occurrenceIndex);
        Set<String> seenAttributes = new HashSet<>();
        for (PluginModifier mod : pluginDef.modifiers()) {
            if (!seenAttributes.add(mod.attribute())) {
                ModularShoot.LOGGER.warn(
                        "Plugin '{}' on gun instance {}: duplicate modifier for attribute '{}' "
                                + "skipped (only the first modifier per attribute takes effect).",
                        instance.pluginId(), instance.instanceUuid(), mod.attribute());
                continue;
            }
            resolveAttributeHolder(mod.attribute()).ifPresent(holder -> {
                AttributeModifier modifier = new AttributeModifier(
                        modifierId, mod.value(), mapOperation(mod.operation()));
                builder.add(holder, modifier, EquipmentSlotGroup.MAINHAND);
            });
        }
    }

    /**
     * Maps a {@link PluginModifier.Operation} to the corresponding vanilla
     * {@link AttributeModifier.Operation} (设计文档 §属性修饰符格式).
     *
     * @param op the plugin-side operation enum
     * @return the vanilla attribute modifier operation
     */
    private static AttributeModifier.Operation mapOperation(PluginModifier.Operation op) {
        return switch (op) {
            case ADD -> AttributeModifier.Operation.ADD_VALUE;
            case MULTIPLY -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case MULTIPLY_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }

    /**
     * Resolves an attribute name string to its registered
     * {@link Holder<Attribute>} in the vanilla {@code ATTRIBUTE} registry.
     *
     * <p>Names without a namespace (e.g. {@code "fire_rate"}) default to the
     * {@code modularshoot} namespace; names with a namespace (e.g.
     * {@code "examplemod:custom_attr"}) are parsed verbatim. This allows
     * plugins to target both framework attributes and third-party attributes.
     *
     * @param attribute the attribute name, with or without namespace
     * @return the registered attribute holder, or empty when no attribute is
     *         registered under that id
     */
    private static Optional<Holder.Reference<Attribute>> resolveAttributeHolder(String attribute) {
        ResourceLocation attrId = attribute.contains(":")
                ? ResourceLocation.parse(attribute)
                : ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, attribute);
        return BuiltInRegistries.ATTRIBUTE.getHolder(attrId);
    }

    /**
     * Builds the stable modifier id for one plugin instance from its
     * {@code pluginId} and its occurrence index in the installed plugin list.
     *
     * <p>The id is intentionally <em>cross-instance stable</em>: it is derived
     * from the plugin's {@code pluginId} and its position, <em>not</em> from
     * the per-gun {@code instanceUuid}. This is what lets
     * {@link #computeAllModifiers}' cache be shared between two players or two
     * copies of the same gun carrying an identical plugin configuration — the
     * id is identical for both, so the same cached
     * {@link ItemAttributeModifiers} is correct for both. The occurrence
     * index (rather than the bare pluginId) also keeps two copies of the same
     * plugin on one gun distinct: they occupy different positions and thus get
     * different ids, so their modifiers never collide on the same attribute.
     * Vanilla keys modifier identity by the (attribute, id) pair, so all
     * modifiers from one plugin instance can share this single id as long as
     * they target distinct attributes (设计文档 §修饰符 ID 稳定性). When a
     * plugin definition declares two modifiers targeting the same attribute,
     * the runtime deduplication guard in
     * {@link #addSinglePluginModifiers} ensures only the first is mounted,
     * preventing vanilla's {@code AttributeInstance.addModifier} from throwing
     * {@code IllegalArgumentException}.
     *
     * <p><strong>Unambiguous encoding (5.2 加固).</strong> The id is encoded
     * with <em>length prefixes</em> so that distinct
     * {@code (namespace, path, occurrenceIndex)} triples can never collide:
     * the namespace and path lengths are written explicitly before their
     * content, so the parser always knows exactly how many characters each
     * field spans even when the namespace/path themselves contain {@code _}
     * or digits. The bare concatenation {@code plugin_<ns>_<path>_<idx>} was
     * ambiguous — e.g. ids {@code a:b_c} (ns = {@code a}, path = {@code b_c})
     * and {@code a_b:c} (ns = {@code a_b}, path = {@code c}) both collapsed to
     * {@code plugin_a_b_c_<idx>}. Under the hardened scheme they instead
     * encode as {@code plugin_1_a_3_b_c_<idx>} and
     * {@code plugin_3_a_b_1_c_<idx>}, which are distinct. Because the lengths
     * are explicit and the {@code occurrenceIndex} is a trailing integer after
     * the fixed-length path, the encoding is a prefix-free field encoding and
     * therefore collision-free for every legal {@link ResourceLocation}
     * namespace/path. All characters used ({@code a-z}, {@code 0-9},
     * {@code _}) are valid in a {@link ResourceLocation} path.</p>
     *
     * @param pluginId         the plugin definition id
     * @param occurrenceIndex the index of the plugin instance in the full
     *                        installed plugin list (stable across guns with
     *                        the same plugin list)
     * @return a {@link ResourceLocation} under the {@code modularshoot} namespace
     */
    private static ResourceLocation pluginModifierId(ResourceLocation pluginId, int occurrenceIndex) {
        String namespace = pluginId.getNamespace();
        String path = pluginId.getPath();
        String encoded = "plugin_"
                + namespace.length() + "_" + namespace + "_"
                + path.length() + "_" + path + "_"
                + occurrenceIndex;
        return ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, encoded);
    }
}
