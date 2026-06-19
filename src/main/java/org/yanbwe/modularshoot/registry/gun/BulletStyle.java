package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * Visual appearance of a gun's projectile.
 *
 * <p>Optional per gun definition; when absent the gun uses a default bullet
 * appearance (a pure collision body with no render object). Installed plugins
 * may override this setting on a per-gun basis.</p>
 *
 * @param model      resource paths keyed by render-mode tag. The conventional
 *                   keys are {@code "3d"} (a vanilla static JSON model path)
 *                   and {@code "billboard"} (a texture path). Both may be
 *                   supplied so the framework can pick at render time.
 * @param renderMode which rendering pipeline the projectile uses
 */
public record BulletStyle(Map<String, ResourceLocation> model, RenderMode renderMode) {

    public static final Codec<BulletStyle> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.unboundedMap(Codec.STRING, ResourceLocation.CODEC).fieldOf("model").forGetter(BulletStyle::model),
                    RenderMode.CODEC.fieldOf("render_mode").forGetter(BulletStyle::renderMode)
            ).apply(instance, BulletStyle::new)
    );

    /**
     * Projectile rendering pipeline.
     *
     * <ul>
     *   <li>{@link #BILLBOARD} — a 2D sprite texture that always faces the
     *       viewer.</li>
     *   <li>{@link #THREE_D} — a vanilla static JSON model.</li>
     * </ul>
     */
    public enum RenderMode implements StringRepresentable {
        BILLBOARD("billboard"),
        THREE_D("3d");

        public static final Codec<RenderMode> CODEC = StringRepresentable.fromEnum(RenderMode::values);

        private final String name;

        RenderMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
