package org.yanbwe.modularshoot.attribute;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Mounts framework attributes onto entities so that
 * {@code entity.getAttribute(ModularShootAttributes.X)} succeeds.
 *
 * <p>Without this event handler the framework attributes — although registered
 * in the vanilla {@code ATTRIBUTE} registry via {@code DeferredRegister} — are
 * never added to any entity's {@code AttributeMap}. Calling
 * {@code getAttributeValue()} on a player whose map does not contain the
 * attribute throws {@code IllegalArgumentException("Can't find attribute …")}
 * and crashes the shoot-request pipeline.</p>
 *
 * <p>All ten framework attributes are pre-mounted onto <em>every</em> entity
 * type in {@link BuiltInRegistries#ENTITY_TYPE} (player stays explicitly
 * mounted first). The cost is negligible: per-entity {@code AttributeInstance}
 * objects are lazily instantiated when an entity spawns and queries its
 * attributes, while the static supplier entries merely record the attribute
 * keys. Which entity types actually <em>take effect</em> is decided at read
 * time by the data-driven whitelist
 * {@code attribute_meta.<id>.entity_types} — entity types outside that list
 * degrade to {@code 0.0} (see {@link AttributeResolver#readFinalValue}), so a
 * datapack reload alone can switch the effect scope without touching code.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootAttributeEvents {

    private ModularShootAttributeEvents() {
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        // 新增框架属性时必须同步维护两处清单：
        // 1) 下方玩家的显式挂载（10 行）；
        // 2) FRAMEWORK_ATTRIBUTES 列表（其余全部实体类型的预挂载源）。
        // 两者必须保持一致，否则会出现玩家与其余实体挂载不一致的属性集。
        event.add(EntityType.PLAYER, ModularShootAttributes.HIT_DAMAGE);
        event.add(EntityType.PLAYER, ModularShootAttributes.FIRE_RATE);
        event.add(EntityType.PLAYER, ModularShootAttributes.RANGE);
        event.add(EntityType.PLAYER, ModularShootAttributes.ACCURACY_YAW);
        event.add(EntityType.PLAYER, ModularShootAttributes.ACCURACY_PITCH);
        event.add(EntityType.PLAYER, ModularShootAttributes.ENTITY_PENETRATION);
        event.add(EntityType.PLAYER, ModularShootAttributes.BULLET_SPEED);
        event.add(EntityType.PLAYER, ModularShootAttributes.BULLET_SIZE);
        event.add(EntityType.PLAYER, ModularShootAttributes.BLOCK_PENETRATION);
        event.add(EntityType.PLAYER, ModularShootAttributes.PELLET_COUNT);

        // 预挂载其余全部实体类型：生效范围由 attribute_meta.entity_types 白名单控制。
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type == EntityType.PLAYER) {
                // 玩家已在上方显式挂载。重复 add 虽然不会抛异常（NeoForge 改造后的
                // AttributeSupplier.Builder 用 HashMap 记录，重复 put 以同值覆盖），
                // 但冗余无意义，此处跳过。
                continue;
            }
            // 与 EntityAttributeModificationEvent 构造器内部相同的 unchecked 转换：
            // 非 LivingEntity 类型（如 minecraft:item）只会得到从未被读取的 supplier 条目。
            @SuppressWarnings("unchecked")
            EntityType<? extends LivingEntity> livingType = (EntityType<? extends LivingEntity>) type;
            for (Holder<Attribute> attribute : FRAMEWORK_ATTRIBUTES) {
                event.add(livingType, attribute);
            }
        }
    }

    /**
     * The ten framework attributes mounted onto every entity type.
     *
     * <p>必须与 {@link #onEntityAttributeModification} 中玩家的显式挂载清单
     * 保持同步：新增框架属性时两处必须同时更新。</p>
     */
    private static final List<Holder<Attribute>> FRAMEWORK_ATTRIBUTES = List.of(
            ModularShootAttributes.HIT_DAMAGE,
            ModularShootAttributes.FIRE_RATE,
            ModularShootAttributes.RANGE,
            ModularShootAttributes.ACCURACY_YAW,
            ModularShootAttributes.ACCURACY_PITCH,
            ModularShootAttributes.ENTITY_PENETRATION,
            ModularShootAttributes.BULLET_SPEED,
            ModularShootAttributes.BULLET_SIZE,
            ModularShootAttributes.BLOCK_PENETRATION,
            ModularShootAttributes.PELLET_COUNT);
}
