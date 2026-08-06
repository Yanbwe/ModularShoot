package org.yanbwe.modularshoot.attribute;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.yanbwe.modularshoot.registry.attribute.AttributeMeta;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

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
 *       plugin instance shares the stable id derived from that instance's
 *       {@code instanceUuid} so vanilla can match and replace them on
 *       install/uninstall (设计文档 §修饰符 ID 稳定性). Because all modifiers
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
 * modifiers use the plugin's {@code instanceUuid} as their id, so vanilla can
 * likewise match and replace them when a plugin is installed or removed
 * (设计文档 §修饰符 ID 稳定性). When a plugin definition declares multiple
 * modifiers targeting the same attribute, only the first is mounted; see
 * {@link #addPluginModifiers} for the deduplication rationale.
 */
public final class AttributeModifierService {

    /** Stable modifier id for gun base values (设计文档 §修饰符 ID 稳定性). */
    public static final ResourceLocation GUN_BASE_MODIFIER_ID =
            ResourceLocation.parse("modularshoot:gun_base");

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
     * modifiers are added
     * afterwards, each keyed by its plugin instance's {@code instanceUuid}.
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
     * modifiers from one plugin instance share the id derived from that
     * instance's {@code instanceUuid} (设计文档 §修饰符 ID 稳定性). Plugins
     * whose definition is missing and modifiers whose target attribute is not
     * registered are silently skipped.</p>
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
        List<PluginInstance> validPlugins =
                PluginDegradationHandler.filterValidPlugins(gunData.installedPlugins(), registryAccess);
        for (PluginInstance instance : validPlugins) {
            Optional<PluginDefinition> pluginDef = PluginRegistry.getPlugin(registryAccess, instance.pluginId());
            if (pluginDef.isEmpty()) {
                continue;
            }
            addSinglePluginModifiers(builder, instance, pluginDef.get());
        }
    }

    /**
     * Adds the attribute modifiers declared by a single installed plugin
     * instance to a builder, skipping duplicate attribute targets.
     *
     * <p>Extracted from {@link #addPluginModifiers} so the per-instance loop
     * body stays under 50 lines. All modifiers from this instance share the
     * id derived from {@code instance.instanceUuid()}. When the plugin
     * definition declares multiple modifiers targeting the same attribute,
     * only the first is mounted; subsequent duplicates are skipped with a
     * {@code WARN} log to prevent vanilla's
     * {@code AttributeInstance.addModifier} from throwing
     * {@code IllegalArgumentException} (W3 fix).</p>
     *
     * @param builder    the builder to append entries to
     * @param instance   the installed plugin instance providing the id
     * @param pluginDef  the plugin definition supplying declared modifiers
     */
    private static void addSinglePluginModifiers(
            ItemAttributeModifiers.Builder builder, PluginInstance instance, PluginDefinition pluginDef) {
        ResourceLocation modifierId = pluginModifierId(instance.instanceUuid());
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
     * Builds the stable modifier id for a plugin instance from its
     * {@code instanceUuid}.
     *
     * <p>The uuid is placed in the path segment under the
     * {@code modularshoot} namespace, yielding ids such as
     * {@code modularshoot:550e8400-e29b-41d4-a716-446655440000}. Vanilla keys
     * modifier identity by the (attribute, id) pair, so all modifiers from one
     * plugin instance can share this single id as long as they target distinct
     * attributes (设计文档 §修饰符 ID 稳定性). When a plugin definition
     * declares two modifiers targeting the same attribute, the runtime
     * deduplication guard in {@link #addSinglePluginModifiers} ensures only
     * the first is mounted, preventing vanilla's
     * {@code AttributeInstance.addModifier} from throwing
     * {@code IllegalArgumentException}.
     *
     * @param instanceUuid the plugin instance's stable uuid
     * @return a {@link ResourceLocation} under the {@code modularshoot} namespace
     */
    private static ResourceLocation pluginModifierId(UUID instanceUuid) {
        return ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, instanceUuid.toString());
    }
}
