package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * How an overlay's colour channels are combined with the underlying pixels
 * during blending.
 *
 * <ul>
 *   <li>{@link #NORMAL} — standard source-over alpha compositing: the overlay
 *       colour replaces the base colour where the overlay is opaque and blends
 *       with it where it is translucent (the legacy behaviour).</li>
 *   <li>{@link #MULTIPLY} — the overlay colour is multiplied with the base,
 *       darkening the result.</li>
 *   <li>{@link #SCREEN} — inverse-multiplied with the base, brightening the
 *       result (filter mode).</li>
 *   <li>{@link #ADD} — the overlay colour is added to the base, clamped to 1,
 *       brightening the result.</li>
 * </ul>
 *
 * <p>Only the colour channels are affected; alpha always uses ordinary over
 * compositing. Defaults to {@link #NORMAL} (the legacy behaviour) when
 * omitted from the JSON.</p>
 */
public enum OverlayBlend implements StringRepresentable {
    NORMAL("normal"),
    MULTIPLY("multiply"),
    SCREEN("screen"),
    ADD("add");

    public static final Codec<OverlayBlend> CODEC = StringRepresentable.fromEnum(OverlayBlend::values);

    private final String name;

    OverlayBlend(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
