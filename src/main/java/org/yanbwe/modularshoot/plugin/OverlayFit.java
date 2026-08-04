package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How a texture overlay is fitted into the gun's base texture canvas before
 * blending.
 *
 * <ul>
 *   <li>{@link #NONE} — no resampling; the overlay keeps its pixel size and
 *       is placed by {@link OverlayAlignment}. Pixels outside the base
 *       canvas are clipped.</li>
 *   <li>{@link #FILL} — resampled (bilinear) to cover the whole canvas;
 *       non-uniform stretching when the aspect ratios differ.</li>
 *   <li>{@link #CONTAIN} — resampled (bilinear) to the largest uniform scale
 *       that keeps the overlay fully visible, centred with transparent
 *       margins when the aspect ratios differ.</li>
 * </ul>
 *
 * <p>Defaults to {@link #NONE} (the legacy behaviour) when omitted from the
 * JSON.</p>
 */
public enum OverlayFit implements StringRepresentable {
    NONE("none"),
    FILL("fill"),
    CONTAIN("contain");

    public static final Codec<OverlayFit> CODEC = StringRepresentable.fromEnum(OverlayFit::values);

    private final String name;

    OverlayFit(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
