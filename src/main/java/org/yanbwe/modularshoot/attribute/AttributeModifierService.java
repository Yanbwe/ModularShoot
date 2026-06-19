package org.yanbwe.modularshoot.attribute;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
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
 * <p>M1 scope: only gun base values are applied. Plugin modifiers (M2) and
 * hot-reload refresh (M6) build on top of this service.
 *
 * <p>Every gun base modifier shares the stable id {@code modularshoot:gun_base}
 * so vanilla can correctly match and replace them on refresh. Different
 * attributes may share this id because vanilla keys modifier identity by the
 * (attribute, id) pair, and each attribute here is distinct.
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
     * Computes the {@link ItemAttributeModifiers} for a gun definition.
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
     *屏蔽原版属性修饰符提示行).
     *
     * @param gunDef         the gun definition supplying declared stats
     * @param registryAccess the runtime registry view (for {@code attribute_meta})
     * @return an immutable {@link ItemAttributeModifiers} with nine main-hand
     *         entries and tooltip hidden
     */
    public static ItemAttributeModifiers computeGunModifiers(GunDefinition gunDef, RegistryAccess registryAccess) {
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        for (Holder<Attribute> attribute : PRESET_ATTRIBUTES) {
            resolveAttributeId(attribute).ifPresent(id -> {
                double baseValue = resolveBaseValue(id, gunDef, registryAccess);
                AttributeModifier modifier = new AttributeModifier(
                        GUN_BASE_MODIFIER_ID, baseValue, AttributeModifier.Operation.ADD_VALUE);
                builder.add(attribute, modifier, EquipmentSlotGroup.MAINHAND);
            });
        }
        return builder.build().withTooltip(false);
    }

    /**
     * Computes and writes the {@code ATTRIBUTE_MODIFIERS} component onto a gun
     * stack. Used at gun creation time and after any change that affects base
     * values (设计文档 §组件刷新时机).
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
     * {@link GunData}.
     *
     * <p>Reads the {@code gun_id} from the stack, looks up the current
     * {@link GunDefinition} and re-applies the modifiers. When the gun
     * definition can no longer be found (e.g. the datapack was removed), the
     * component is reset to {@link ItemAttributeModifiers#EMPTY} so stale
     * modifiers are cleared rather than left dangling.
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
            applyModifiers(gunStack, gunDef.get(), registryAccess);
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
}
