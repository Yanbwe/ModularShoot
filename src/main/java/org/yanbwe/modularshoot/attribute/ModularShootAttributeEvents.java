package org.yanbwe.modularshoot.attribute;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Mounts framework attributes onto entities so that
 * {@code player.getAttributeValue(ModularShootAttributes.X)} succeeds.
 *
 * <p>Without this event handler the framework attributes — although registered
 * in the vanilla {@code ATTRIBUTE} registry via {@code DeferredRegister} — are
 * never added to any entity's {@code AttributeMap}. Calling
 * {@code getAttributeValue()} on a player whose map does not contain the
 * attribute throws {@code IllegalArgumentException("Can't find attribute …")}
 * and crashes the shoot-request pipeline.</p>
 *
 * <p>All nine framework attributes are added to {@link EntityType#PLAYER} so
 * that the shooting engine, attribute-modifier service and tooltip builders
 * can safely read final values from any player.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootAttributeEvents {

    private ModularShootAttributeEvents() {
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, ModularShootAttributes.HIT_DAMAGE);
        event.add(EntityType.PLAYER, ModularShootAttributes.FIRE_RATE);
        event.add(EntityType.PLAYER, ModularShootAttributes.RANGE);
        event.add(EntityType.PLAYER, ModularShootAttributes.ACCURACY_YAW);
        event.add(EntityType.PLAYER, ModularShootAttributes.ACCURACY_PITCH);
        event.add(EntityType.PLAYER, ModularShootAttributes.ENTITY_PENETRATION);
        event.add(EntityType.PLAYER, ModularShootAttributes.BULLET_SPEED);
        event.add(EntityType.PLAYER, ModularShootAttributes.BULLET_SIZE);
        event.add(EntityType.PLAYER, ModularShootAttributes.BLOCK_PENETRATION);
    }
}
