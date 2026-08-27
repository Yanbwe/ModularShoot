package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Defines where a gun attribute modifier is mounted.
 *
 * <p>Defaults to {@link #ITEM}. With {@link #ITEM}, the framework writes the
 * base value and plugin modifiers into the item's {@code ATTRIBUTE_MODIFIERS}
 * component and the vanilla main-hand system applies them to the player.</p>
 *
 * <p>{@link #PLAYER} means the framework does not manage the item-side
 * attribute modifier component; mounting responsibility is transferred to the
 * declaring side (for example, OneGunLifetime mounts the modifier directly on
 * the player entity).</p>
 */
public enum AttributeMount implements StringRepresentable {
    ITEM("item"),
    PLAYER("player");

    public static final Codec<AttributeMount> CODEC = StringRepresentable.fromEnum(AttributeMount::values);

    private final String name;

    AttributeMount(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}