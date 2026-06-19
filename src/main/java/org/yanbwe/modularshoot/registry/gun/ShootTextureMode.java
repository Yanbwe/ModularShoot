package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Controls when the shoot texture is swapped during firing.
 *
 * <p>Only meaningful when a gun defines a shoot texture; the field has no
 * effect on guns that use only the base texture. Defaults to
 * {@link #PER_SHOT} when omitted from the gun JSON.</p>
 *
 * <ul>
 *   <li>{@link #PER_SHOT} — swap to the shoot texture on every shot and snap
 *       back immediately afterwards. Gives a crisp single-shot feel; on
 *       high-rate weapons the texture flickers per round.</li>
 *   <li>{@link #WHILE_FIRING} — hold the shoot texture for as long as the
 *       trigger is pressed, then revert on release. Suited to full-auto
 *       weapons where per-shot flicker would be distracting.</li>
 * </ul>
 */
public enum ShootTextureMode implements StringRepresentable {
    PER_SHOT("per_shot"),
    WHILE_FIRING("while_firing");

    public static final Codec<ShootTextureMode> CODEC = StringRepresentable.fromEnum(ShootTextureMode::values);

    private final String name;

    ShootTextureMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
