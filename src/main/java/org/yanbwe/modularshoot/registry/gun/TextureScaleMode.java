package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Controls how the item geometry scales with its texture resolution.
 *
 * <p>By default ({@link #AUTO}) the rendered quad is sized from the texture
 * pixel dimensions: a 32×32 PNG renders 2× larger than a 16×16 one (16 px =
 * 1 grid cell, width/height scaled independently so non-square textures are
 * never stretched, extrusion thickness stays at 1/16 cell). {@link #FIXED}
 * keeps the legacy behaviour of always fitting the texture into the 16×16
 * unit grid regardless of resolution.</p>
 *
 * <p>Applies to the gun's base / shoot texture and the plugin's
 * {@code item_icon}. Defaults to {@link #AUTO} when omitted from the JSON.</p>
 */
public enum TextureScaleMode implements StringRepresentable {
    AUTO("auto"),
    FIXED("fixed");

    public static final Codec<TextureScaleMode> CODEC = StringRepresentable.fromEnum(TextureScaleMode::values);

    private final String name;

    TextureScaleMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
