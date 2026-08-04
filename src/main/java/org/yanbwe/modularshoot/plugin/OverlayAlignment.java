package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Nine-grid alignment of a texture overlay within the gun's base texture
 * canvas.
 *
 * <p>Only meaningful when the overlay's {@code fit} is {@link OverlayFit#NONE}
 * — {@code fill} covers the whole canvas and {@code contain} is always
 * centred, so neither has an alignment concept. Defaults to
 * {@link #TOP_LEFT} (the legacy corner-aligned behaviour) when omitted from
 * the JSON.</p>
 */
public enum OverlayAlignment implements StringRepresentable {
    TOP_LEFT("top_left"),
    TOP_CENTER("top_center"),
    TOP_RIGHT("top_right"),
    CENTER_LEFT("center_left"),
    CENTER("center"),
    CENTER_RIGHT("center_right"),
    BOTTOM_LEFT("bottom_left"),
    BOTTOM_CENTER("bottom_center"),
    BOTTOM_RIGHT("bottom_right");

    public static final Codec<OverlayAlignment> CODEC = StringRepresentable.fromEnum(OverlayAlignment::values);

    private final String name;

    OverlayAlignment(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
