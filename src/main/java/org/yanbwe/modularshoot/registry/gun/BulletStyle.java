package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * Visual appearance of a gun's projectile, expressed as a base appearance
 * plus a list of stacking {@link Modifier}s (设计规格 §3.1 / §3.4). Replaces the
 * earlier "overall override" {@code model map + render mode} structure with a
 * composable one: installed plugins, active traits and state conditions all
 * contribute modifiers that <b>stack</b> with this style rather than
 * overwrite it (see
 * {@link org.yanbwe.modularshoot.bullet.VisualCompositionService}).
 *
 * <h2>JSON shape</h2>
 *
 * <pre>{@code
 * "bullet_style": {
 *   "base": {
 *     "render_mode": "billboard",
 *     "texture": "modularshoot:textures/bullet/default.png"
 *   },
 *   "modifiers": [
 *     { "type": "scale", "value": 1.2 },
 *     { "type": "tint",  "color": [1.0, 0.3, 0.2] },
 *     { "type": "attach_layer", "render_mode": "billboard",
 *       "texture": "modularshoot:textures/bullet/flame.png",
 *       "follow_scale": true, "offset": [0.0, 0.1, -0.3], "scale": 0.8 }
 *   ]
 * }
 * }</pre>
 *
 * <p>Both {@code base} and {@code modifiers} are optional; an all-absent
 * {@link BulletStyle} is valid (the framework falls back to a default bullet
 * appearance at compose time, see
 * {@link org.yanbwe.modularshoot.bullet.ComposedBulletStyle#FALLBACK_BASE}).
 * The {@code modifiers} field uses the per-element-tolerant
 * {@link Modifier#LIST_CODEC} so a single unrecognised {@code "type"} key
 * decodes to an {@link UnsupportedModifier} sentinel rather than failing
 * the whole list (spec §5).</p>
 *
 * @param base      the base appearance rendered underneath any layers; absent
 *                  when this source declares no base (the higher-priority
 *                  source's base, or the framework fallback, wins)
 * @param modifiers the stacking modifiers declared by this source; empty when
 *                  none, in source order
 */
public record BulletStyle(Optional<Base> base, List<Modifier> modifiers) {

    public static final Codec<BulletStyle> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Base.CODEC.optionalFieldOf("base").forGetter(BulletStyle::base),
                    Modifier.LIST_CODEC.optionalFieldOf("modifiers", List.of())
                            .forGetter(BulletStyle::modifiers)
            ).apply(instance, BulletStyle::new)
    );

    /**
     * Base appearance of a {@link BulletStyle}: a render mode plus exactly one
     * of texture (billboard) or model (3d). {@link #texture} and {@link #model}
     * are {@code @Nullable}; the codec encodes the {@code null} one by
     * omitting the field and decodes a missing field back to {@code null}
     * (preserving the record contract asserted by tests).
     *
     * @param renderMode which rendering pipeline the projectile uses
     * @param texture    billboard texture path, {@code null} when base is 3d
     * @param model      3d model path, {@code null} when base is billboard
     */
    public record Base(
            RenderMode renderMode,
            @Nullable ResourceLocation texture,
            @Nullable ResourceLocation model) {

        public static final Codec<Base> CODEC = RecordCodecBuilder.create(
                instance -> instance.group(
                        RenderMode.CODEC.fieldOf("render_mode").forGetter(Base::renderMode),
                        ResourceLocation.CODEC.optionalFieldOf("texture").xmap(
                                Optional::orElse, Optional::ofNullable).forGetter(Base::texture),
                        ResourceLocation.CODEC.optionalFieldOf("model").xmap(
                                Optional::orElse, Optional::ofNullable).forGetter(Base::model)
                ).apply(instance, Base::new));

    }

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