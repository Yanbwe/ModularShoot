package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.function.Function;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom {@link RenderType} definitions for bullet rendering
 * (设计文档 §渲染流程, line 1240).
 *
 * <p>Provides a <em>billboard</em> RenderType that combines the state shards
 * required for camera-facing quad bullets:</p>
 * <ul>
 *   <li><b>Depth test</b> — LEQUAL, so bullets are correctly occluded by
 *       closer terrain and entities</li>
 *   <li><b>Transparency</b> — translucent (src-alpha / one-minus-src-alpha),
 *       standard alpha blending for soft sprite edges</li>
 *   <li><b>No lightmap</b> — the bullet is always full-bright (emissive),
 *       unaffected by world lighting, matching the design-doc requirement
 *       for "无光照" rendering</li>
 *   <li><b>No cull</b> — the quad is visible from both sides, so the bullet
 *       never disappears when the camera sweeps past it</li>
 *   <li><b>Output target</b> — item-entity target, composited in the
 *       fabulous transparency pass alongside other translucent entities</li>
 * </ul>
 *
 * <p><b>Creation mechanism.</b> The RenderType is built via
 * {@link RenderType#create}, which NeoForge exposes as {@code public}
 * through its built-in Access Transformer (the vanilla method is
 * package-private). The {@link RenderType.CompositeState} builder assembles
 * the state shards, mirroring how vanilla builds
 * {@code RenderType.eyes(ResourceLocation)} — but with translucent blending
 * (instead of additive) and depth-write enabled for correct occlusion of
 * in-flight bullets. The {@code rendertype_eyes} shader is chosen because it
 * is the canonical vanilla shader that requires neither a lightmap nor an
 * overlay sampler, making it the safe pairing for NO_LIGHTMAP / NO_OVERLAY.</p>
 *
 * <p><b>Memoization.</b> RenderTypes are cached per texture via
 * {@link Util#memoize}, so all bullets sharing the same texture reuse a
 * single RenderType instance. This lets the {@code BufferSource} batch
 * their geometry into one vertex buffer instead of allocating a new buffer
 * every frame.</p>
 *
 * <p><strong>Client-only class.</strong> Lives in the client render package
 * and must only be referenced from client-side rendering code.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see BillboardRenderer
 * @see BulletRenderObject#getTexture()
 */
public final class BulletRenderType {

    /** Buffer size for a quad batch, matching vanilla entity RenderTypes. */
    private static final int BUFFER_SIZE = 1536;

    /**
     * Memoized billboard RenderType factory — one RenderType per distinct
     * texture, so identical textures share a single buffer in the
     * {@code BufferSource}.
     */
    private static final Function<ResourceLocation, RenderType> BILLBOARD =
            Util.memoize(BulletRenderType::createBillboard);

    private BulletRenderType() {
    }

    /**
     * Returns the billboard RenderType bound to the given texture.
     *
     * <p>The result is cached: calling this method twice with the same
     * {@code ResourceLocation} returns the same {@code RenderType} instance,
     * enabling vertex-buffer reuse across bullets that share a texture.</p>
     *
     * @param texture the billboard sprite texture; must not be {@code null}
     * @return a memoized composite RenderType for the texture
     */
    public static RenderType billboard(ResourceLocation texture) {
        return BILLBOARD.apply(texture);
    }

    /**
     * Builds the composite RenderType for a single billboard texture
     * (设计文档 §渲染流程, line 1240).
     *
     * <p>State shards assembled:</p>
     * <ul>
     *   <li>Shader — {@code rendertype_eyes} (emissive, no lightmap needed)</li>
     *   <li>Texture — the bullet sprite, no blur, no mipmap</li>
     *   <li>Transparency — translucent (standard alpha blend)</li>
     *   <li>Depth test — LEQUAL (occluded by closer terrain)</li>
     *   <li>Cull — disabled (visible from both sides)</li>
     *   <li>Lightmap — disabled (always full-bright)</li>
     *   <li>Overlay — disabled (no hurt/damage overlay)</li>
     *   <li>Output — item-entity target (fabulous transparency pass)</li>
     * </ul>
     *
     * @param texture the billboard sprite texture
     * @return a new composite RenderType
     */
    private static RenderType createBillboard(ResourceLocation texture) {
        return RenderType.create(
                "modularshoot:billboard",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                BUFFER_SIZE,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .createCompositeState(false)
        );
    }
}
