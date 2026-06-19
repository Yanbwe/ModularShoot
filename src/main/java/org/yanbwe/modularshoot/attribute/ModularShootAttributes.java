package org.yanbwe.modularshoot.attribute;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.yanbwe.modularshoot.ModularShoot;

/**
 * Framework preset attributes registered to the vanilla {@code ATTRIBUTE} registry.
 *
 * <p>All attributes use a vanilla base value of {@code 0}: a player holding no gun has zero
 * framework stats, and gun base values are applied through attribute modifiers rather than the
 * vanilla base. The "default values" from the design document live in the {@code attribute_meta}
 * metadata table (hot-reloadable) and are distinct from this vanilla base.
 *
 * <p>Every attribute is {@code syncable} so the client receives final values for tooltip display.
 */
public final class ModularShootAttributes {

    /** Vanilla base for every framework attribute; gun base values come from modifiers. */
    private static final double BASE_VALUE = 0.0;

    /** Deferred register bound to the vanilla {@code ATTRIBUTE} registry under the mod namespace. */
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, ModularShoot.MODID);

    // --- Preset attributes (base = 0, syncable) ---

    public static final DeferredHolder<Attribute, Attribute> HIT_DAMAGE =
            register("hit_damage", 0.0, 2048.0);
    public static final DeferredHolder<Attribute, Attribute> FIRE_RATE =
            register("fire_rate", 0.0, 1024.0);
    public static final DeferredHolder<Attribute, Attribute> RANGE =
            register("range", 0.0, 2048.0);
    public static final DeferredHolder<Attribute, Attribute> ACCURACY_YAW =
            register("accuracy_yaw", 0.0, 360.0);
    public static final DeferredHolder<Attribute, Attribute> ACCURACY_PITCH =
            register("accuracy_pitch", 0.0, 360.0);
    public static final DeferredHolder<Attribute, Attribute> ENTITY_PENETRATION =
            register("entity_penetration", 0.0, 1024.0);
    public static final DeferredHolder<Attribute, Attribute> BULLET_SPEED =
            register("bullet_speed", 0.0, 2048.0);
    public static final DeferredHolder<Attribute, Attribute> BULLET_SIZE =
            register("bullet_size", 0.0, 1024.0);
    public static final DeferredHolder<Attribute, Attribute> BLOCK_PENETRATION =
            register("block_penetration", 0.0, 1024.0);

    private ModularShootAttributes() {
    }

    /**
     * Registers a ranged framework attribute with the vanilla base of {@code 0}, clamped to
     * {@code [min, max]} and synced to clients.
     */
    private static DeferredHolder<Attribute, Attribute> register(String name, double min, double max) {
        return ATTRIBUTES.register(name, () ->
                new RangedAttribute(descriptionId(name), BASE_VALUE, min, max).setSyncable(true));
    }

    /** Builds the translation key for a framework attribute: {@code attribute.name.modularshoot.<name>}. */
    private static String descriptionId(String name) {
        return "attribute.name." + ModularShoot.MODID + "." + name;
    }

    // --- Holder accessors (for modifier mounting) ---

    public static Holder<Attribute> hitDamageHolder() {
        return HIT_DAMAGE;
    }

    public static Holder<Attribute> fireRateHolder() {
        return FIRE_RATE;
    }

    public static Holder<Attribute> rangeHolder() {
        return RANGE;
    }

    public static Holder<Attribute> accuracyYawHolder() {
        return ACCURACY_YAW;
    }

    public static Holder<Attribute> accuracyPitchHolder() {
        return ACCURACY_PITCH;
    }

    public static Holder<Attribute> entityPenetrationHolder() {
        return ENTITY_PENETRATION;
    }

    public static Holder<Attribute> bulletSpeedHolder() {
        return BULLET_SPEED;
    }

    public static Holder<Attribute> bulletSizeHolder() {
        return BULLET_SIZE;
    }

    public static Holder<Attribute> blockPenetrationHolder() {
        return BLOCK_PENETRATION;
    }
}
