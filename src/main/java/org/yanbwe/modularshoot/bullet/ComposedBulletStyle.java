package org.yanbwe.modularshoot.bullet;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

/**
 * Immutable aggregated visual style produced by
 * {@link VisualCompositionService#compose} at bullet creation time
 * (设计规格 §3.4 / §4.2 算法末). One bullet corresponds to exactly one
 * {@code ComposedBulletStyle}, cached on
 * {@link org.yanbwe.modularshoot.bullet.BulletRecord} for the bullet's entire
 * lifetime (设计规格 §2.1 "创建瞬间冻结"). Subsequent
 * {@code BulletSyncService.toFullBulletEntry} invocations re-read this cached
 * value rather than recomputing the composition each tick.
 *
 * @param base         the resolved base appearance (last-wins by priority +
 *                     install order; framework {@link #FALLBACK_BASE} when no
 *                     source declared a base)
 * @param renderScale  product of all top-level {@code ScaleModifier.value}
 *                     across all sources, clamped finite &gt; 0; defaults
 *                     {@code 1.0}
 * @param composedTint channel-wise product of all top-level
 *                     {@code TintModifier} colors; {@link #WHITE_TINT}
 *                     (1,1,1,1) when none; never {@code null}. A transport
 *                     layer ({@code FullBulletEntry}) may collapse an
 *                     all-white tint to a {@code null} wire sentinel to save
 *                     4 bytes per bullet; the field here is always concrete.
 * @param layers       all {@code attach_layer} modifiers across all sources,
 *                     in source order; empty when none
 */
public record ComposedBulletStyle(
        BulletStyle.Base base,
        float renderScale,
        Vector4f composedTint,
        List<LayerEntry> layers) {

    /** White identity tint — used when no source contributed any tint. */
    public static final Vector4f WHITE_TINT = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    /** Framework fallback base: billboard + the conventional default texture. */
    public static final BulletStyle.Base FALLBACK_BASE = new BulletStyle.Base(
            BulletStyle.RenderMode.BILLBOARD,
            Optional.of(ResourceLocation.fromNamespaceAndPath("modularshoot", "textures/bullet/default.png")),
            Optional.empty());

    /** Default composed style for "everything missing" degradation (spec §5 last two rows). */
    public static final ComposedBulletStyle DEFAULT = new ComposedBulletStyle(
            FALLBACK_BASE, 1.0f, WHITE_TINT, List.of());

    /**
     * Per-layer serialised data — one entry per {@code attach_layer} modifier
     * across all sources (spec §4.3 LayerEntry). Float scalars rather than
     * Vector4f/Vec3 keep the wire record flat (matches
     * {@code FullBulletEntry.LayerEntryFull} style).
     *
     * @param renderMode      {@code "billboard"} or {@code "3d"} (never stored
     *                        as a string here, the enum preserves domain
     *                        type; the transport layer passes
     *                        {@link BulletStyle.RenderMode#getSerializedName()})
     * @param texture         billboard texture path, {@code null} for 3d
     *                        layers
     * @param model           3d model path, {@code null} for billboard
     *                        layers
     * @param followRotation  whether the layer rotates with the bullet's
     *                        flight direction
     * @param followScale     whether the layer inherits the base
     *                        {@code renderScale}
     * @param offsetX/Y/Z     positional offset relative to base center
     * @param scale           per-layer scale multiplier
     * @param tint            per-layer tint, defaults white; never
     *                        {@code null} —
     *                        {@link org.yanbwe.modularshoot.bullet.VisualCompositionService}
     *                        always substitutes {@link #WHITE_TINT} for a
     *                        missing tint channel
     */
    public record LayerEntry(
            BulletStyle.RenderMode renderMode,
            @Nullable ResourceLocation texture,
            @Nullable ResourceLocation model,
            boolean followRotation,
            boolean followScale,
            float offsetX, float offsetY, float offsetZ,
            float scale,
            Vector4f tint) {
    }
}