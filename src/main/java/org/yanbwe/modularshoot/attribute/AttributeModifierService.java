package org.yanbwe.modularshoot.attribute;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
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
 * <p>The framework registers its preset attributes with a vanilla base of
 * {@code 0}; a gun's base values are applied through {@code ADD_VALUE}
 * modifiers written into the item's {@link DataComponents#ATTRIBUTE_MODIFIERS}
 * component. Vanilla's {@code LivingEntity.detectEquipmentUpdates} then mounts
 * these modifiers onto any player holding the gun in the main hand, so the
 * framework never manages player-side modifiers itself (设计文档 §系统五).
 *
 * <p>The service merges two sources of modifiers into one component:
 * <ul>
 *   <li><b>Gun base values</b> &mdash; one {@code ADD_VALUE} modifier per
 *       preset attribute, all sharing the stable id
 *       {@link #GUN_BASE_MODIFIER_ID} ({@code modularshoot:gun_base}).</li>
 *   <li><b>Plugin modifiers</b> &mdash; the modifiers declared by each
 *       installed plugin ({@link PluginDefinition#modifiers()}), mapped from
 *       the plugin's {@link PluginModifier.Operation} to the corresponding
 *       vanilla {@link AttributeModifier.Operation}. Every modifier from one
 *       plugin instance shares the stable id derived from that instance's
 *       {@code instanceUuid} so vanilla can match and replace them on
 *       install/uninstall (设计文档 §修饰符 ID 稳定性).</li>
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
 * (设计文档 §修饰符 ID 稳定性).
 */
public final class AttributeModifierService {

    /** Stable modifier id for gun base values (设计文档 §修饰符 ID 稳定性). */
    public static final ResourceLocation GUN_BASE_MODIFIER_ID =
            ResourceLocation.parse("modularshoot:gun_base");

    /**
     * The nine framework preset attributes, in display-priority order. Each
     * produces one {@code ADD_VALUE} modifier bound to the main hand.
     */
    private static final List<Holder<Attribute>> PRESET_ATTRIBUTES = List.of(
            ModularShootAttributes.HIT_DAMAGE,
            ModularShootAttributes.FIRE_RATE,
            ModularShootAttributes.RANGE,
            ModularShootAttributes.ACCURACY_YAW,
            ModularShootAttributes.ACCURACY_PITCH,
            ModularShootAttributes.ENTITY_PENETRATION,
            ModularShootAttributes.BULLET_SPEED,
            ModularShootAttributes.BULLET_SIZE,
            ModularShootAttributes.BLOCK_PENETRATION
    );

    private AttributeModifierService() {
    }

    /**
     * Computes the {@link ItemAttributeModifiers} for a gun definition,
     * applying <em>only</em> the gun base values.
     *
     * <p>For each preset attribute the base value is resolved as follows:
     * <ol>
     *   <li>if the gun definition declares the attribute in {@code stats},
     *       use that declared value;</li>
     *   <li>otherwise look up the {@code attribute_meta} default value;</li>
     *   <li>if neither is available, fall back to {@code 0.0}.</li>
     * </ol>
     * Each attribute produces one {@code ADD_VALUE} modifier with the stable
     * {@link #GUN_BASE_MODIFIER_ID}, bound to {@link EquipmentSlotGroup#MAINHAND}
     * with {@code showInTooltip = false} (the framework renders its own tooltip,
     * 屏蔽原版属性修饰符提示行).
     *
     * <p>This method is retained for backward compatibility with M1 callers
     * that create a gun with an empty plugin list. Use
     * {@link #computeAllModifiers} when installed plugins must also be merged.
     *
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view (for {@code attribute_meta})
     * @return an immutable {@link ItemAttributeModifiers} with nine main-hand
     *         entries and tooltip hidden
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
     * <p>Base values are added first (one {@code ADD_VALUE} per preset
     * attribute, id {@link #GUN_BASE_MODIFIER_ID}); plugin modifiers are added
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
     * Resolves the registered id of an attribute holder.
     *
     * @param attribute the attribute holder
     * @return the attribute's {@link ResourceLocation}, or empty for an
     *         unregistered (direct) holder
     */
    private static Optional<ResourceLocation> resolveAttributeId(Holder<Attribute> attribute) {
        return attribute.unwrapKey().map(ResourceKey::location);
    }

    /**
     * Resolves the gun base value for a single attribute.
     *
     * <p>Declared gun stats take precedence; the {@code attribute_meta}
     * default value is the fallback; {@code 0.0} is the last-resort fallback
     * when the metadata entry is also missing.
     *
     * @param attributeId    the attribute id to resolve
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view
     * @return the base value to feed into the {@code ADD_VALUE} modifier
     */
    private static double resolveBaseValue(
            ResourceLocation attributeId, GunDefinition gunDef, RegistryAccess registryAccess) {
        Double declared = gunDef.stats().get(attributeId);
        if (declared != null) {
            return declared;
        }
        return metaDefaultValue(registryAccess, attributeId).orElse(0.0);
    }

    /**
     * Looks up the default value for an attribute from the
     * {@code attribute_meta} registry.
     *
     * @param registryAccess the runtime registry view
     * @param attributeId    the attribute id whose metadata default is sought
     * @return the default value, or empty when the registry or entry is absent
     */
    private static Optional<Double> metaDefaultValue(RegistryAccess registryAccess, ResourceLocation attributeId) {
        return registryAccess.registry(ModularShootRegistries.ATTRIBUTE_META_KEY)
                .flatMap(reg -> reg.getOptional(attributeId))
                .map(AttributeMeta::defaultValue);
    }

    /**
     * Adds the gun base value modifiers for all preset attributes to a builder.
     *
     * <p>Each preset attribute produces one {@code ADD_VALUE} modifier with the
     * stable {@link #GUN_BASE_MODIFIER_ID}, bound to
     * {@link EquipmentSlotGroup#MAINHAND}. Extracted from
     * {@link #computeGunModifiers} so {@link #computeAllModifiers} can reuse the
     * same logic before appending plugin modifiers.
     *
     * @param builder        the builder to append entries to
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view (for {@code attribute_meta})
     */
    private static void addBaseModifiers(
            ItemAttributeModifiers.Builder builder, GunDefinition gunDef, RegistryAccess registryAccess) {
        for (Holder<Attribute> attribute : PRESET_ATTRIBUTES) {
            resolveAttributeId(attribute).ifPresent(id -> {
                double baseValue = resolveBaseValue(id, gunDef, registryAccess);
                AttributeModifier modifier = new AttributeModifier(
                        GUN_BASE_MODIFIER_ID, baseValue, AttributeModifier.Operation.ADD_VALUE);
                builder.add(attribute, modifier, EquipmentSlotGroup.MAINHAND);
            });
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
     * registered are silently skipped.
     *
     * @param builder        the builder to append entries to
     * @param gunData        the per-gun data carrying the installed plugin list
     * @param registryAccess the runtime registry view (for
     *                       {@code modularshoot:plugins})
     */
    private static void addPluginModifiers(
            ItemAttributeModifiers.Builder builder, GunData gunData, RegistryAccess registryAccess) {
        for (PluginInstance instance : gunData.installedPlugins()) {
            Optional<PluginDefinition> pluginDef = PluginRegistry.getPlugin(registryAccess, instance.pluginId());
            if (pluginDef.isEmpty()) {
                continue;
            }
            ResourceLocation modifierId = pluginModifierId(instance.instanceUuid());
            for (PluginModifier mod : pluginDef.get().modifiers()) {
                resolveAttributeHolder(mod.attribute()).ifPresent(holder -> {
                    AttributeModifier modifier = new AttributeModifier(
                            modifierId, mod.value(), mapOperation(mod.operation()));
                    builder.add(holder, modifier, EquipmentSlotGroup.MAINHAND);
                });
            }
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
     * attributes (设计文档 §修饰符 ID 稳定性).
     *
     * @param instanceUuid the plugin instance's stable uuid
     * @return a {@link ResourceLocation} under the {@code modularshoot} namespace
     */
    private static ResourceLocation pluginModifierId(UUID instanceUuid) {
        return ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, instanceUuid.toString());
    }
}
