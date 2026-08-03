package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

/**
 * Additive layer modifier (设计规格 §3.2 "attach_layer"). An independent
 * visual layer drawn on top of the bullet's base appearance, with its own
 * render mode, texture or 3d model, follow parameters, offset, per-layer
 * scale and per-layer tint. Layers compose additively and <b>do not</b>
 * participate in the base {@code renderScale}/{@code composedTint}
 * multiplications — only the per-layer scale/tint apply to the layer
 * (spec §4.2 step 5).
 *
 * @param renderMode     {@code billboard} or {@code 3d} for this layer
 * @param texture        billboard texture path; {@code null} for 3d layers
 * @param model          3d model path; {@code null} for billboard layers
 * @param followRotation whether the layer rotates with the bullet's flight
 *                       direction (billboard layers ignore this — billboards
 *                       always face the camera regardless)
 * @param followScale    whether the layer inherits the base renderScale; when
 *                       true the layer's effective scale is
 *                       {@code baseRenderScale × scale}, otherwise just
 *                       {@code scale}
 * @param offset         positional offset relative to the base center
 * @param scale          per-layer scale multiplier
 * @param tint           per-layer tint; {@code (1,1,1,1)} white identity
 */
public record AttachLayerModifier(
        BulletStyle.RenderMode renderMode,
        @Nullable ResourceLocation texture,
        @Nullable ResourceLocation model,
        boolean followRotation,
        boolean followScale,
        Vec3 offset,
        float scale,
        Vector4f tint) implements Modifier {

    /** Canonical type tag ({@code "attach_layer"}). Cited by {@link Modifier#CODEC}. */
    public static final String TYPE_NAME = "attach_layer";

    /**
     * Per-record {@link MapCodec}. Dispatch consumes the {@code "type"} field;
     * this codec declares only the payload fields. Nullable
     * {@link ResourceLocation}s are encoded via the
     * {@code optionalFieldOf().xmap(orElse(null), Optional.ofNullable)} idiom
     * so a {@code null} omits the field on the wire (canonical) and a missing
     * field decodes back to {@code null} (preserving the @Nullable record
     * field type asserted by tests).
     */
    public static final MapCodec<AttachLayerModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BulletStyle.RenderMode.CODEC.fieldOf("render_mode").forGetter(AttachLayerModifier::renderMode),
                    ResourceLocation.CODEC.optionalFieldOf("texture").xmap(
                            Optional::orElse, Optional::ofNullable).forGetter(AttachLayerModifier::texture),
                    ResourceLocation.CODEC.optionalFieldOf("model").xmap(
                            Optional::orElse, Optional::ofNullable).forGetter(AttachLayerModifier::model),
                    Codec.BOOL.optionalFieldOf("follow_rotation", false).forGetter(AttachLayerModifier::followRotation),
                    Codec.BOOL.optionalFieldOf("follow_scale", true).forGetter(AttachLayerModifier::followScale),
                    Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(AttachLayerModifier::offset),
                    Codec.FLOAT.optionalFieldOf("scale", 1.0f).forGetter(AttachLayerModifier::scale),
                    SharedColorCodecs.VECTOR4F.optionalFieldOf("tint",
                            new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)).forGetter(AttachLayerModifier::tint)
            ).apply(instance, AttachLayerModifier::new));

    @Override
    public String type() {
        return TYPE_NAME;
    }
}