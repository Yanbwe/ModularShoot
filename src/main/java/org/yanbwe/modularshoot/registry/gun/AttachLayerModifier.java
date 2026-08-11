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
 * <p>As of the任务9修正，{@link #texture} and {@link #model} use
 * {@link Optional} (the DFU 1.21.1 idiom) rather than {@code @Nullable}
 * because {@code OptionalFieldCodec} wraps both absent and present fields
 * in a {@code Optional} carrier that survives a {@link RecordCodecBuilder}
 * applicative merge without the null {@link DataResult} NPE that an
 * {@code optionalFieldOf(name, null)} would trip. Callers that want a
 * nullable view should call {@code texture().orElse(null)}.</p>
 *
 * @param renderMode     {@code billboard} or {@code 3d} for this layer
 * @param texture        billboard texture path; empty for 3d layers
 * @param model          3d model path; empty for billboard layers
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
        Optional<ResourceLocation> texture,
        Optional<ResourceLocation> model,
        boolean followRotation,
        boolean followScale,
        Vec3 offset,
        float scale,
        Vector4f tint) implements Modifier {

    /** Canonical type tag ({@code "attach_layer"}). Cited by {@link Modifier#CODEC}. */
    public static final String TYPE_NAME = "attach_layer";

    /**
     * Compact constructor with a defensive copy of {@code tint}.
     *
     * <p>The codec's {@code optionalFieldOf("tint", ...)} default is a single
     * {@link Vector4f} instance shared by every decode that omits the field;
     * without the copy, all such instances would alias one mutable object and
     * an in-place mutation anywhere would globally pollute every omitted-tint
     * layer (稳健性修复).</p>
     *
     * @param tint the layer tint; the stored value is a copy of the argument
     */
    public AttachLayerModifier {
        tint = new Vector4f(tint);
    }

    /**
     * Per-record {@link MapCodec}. Dispatch consumes the {@code "type"} field;
     * this codec declares only the payload fields. Optional
     * {@link ResourceLocation}s use {@link Codec#optionalFieldOf(String)}
     * (no default value), which returns a {@code MapCodec<Optional<A>>} that
     * encodes an empty {@link Optional} by omitting the field on the wire
     * and decodes a missing field back to {@link Optional#empty()} (the
     * canonical DFU 1.21.1 idiom for nullable fields).
     */
    public static final MapCodec<AttachLayerModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BulletStyle.RenderMode.CODEC.fieldOf("render_mode").forGetter(AttachLayerModifier::renderMode),
                    ResourceLocation.CODEC.optionalFieldOf("texture").forGetter(AttachLayerModifier::texture),
                    ResourceLocation.CODEC.optionalFieldOf("model").forGetter(AttachLayerModifier::model),
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