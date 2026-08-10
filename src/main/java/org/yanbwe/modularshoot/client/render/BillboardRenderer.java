package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Renders a billboard (camera-facing quad) for a single in-flight bullet
 * (设计文档 §渲染流程, line 1240).
 *
 * <p>Constructs a 4-vertex quad oriented to face the camera using the
 * camera's right and up vectors, then submits it through the custom
 * {@link BulletRenderType billboard RenderType} that enables depth test,
 * translucent blending, no depth write, no lightmap and no cull. The quad is
 * centered on the bullet's interpolated position — the {@link PoseStack} is
 * already translated there by {@link BulletRenderDispatcher} — and scaled by
 * {@link BulletRenderObject#getScale()}.</p>
 *
 * <p><b>Vertex layout</b> (viewed from the camera, world-aligned axes):</p>
 * <pre>
 *      up
 *      ↑   v3 ────── v2
 *      │   │         │
 *      │   │  quad   │
 *      │   │         │
 *      │   v0 ────── v1
 *      └──────────────→ right
 * </pre>
 * UVs map the full texture so the sprite appears right-side up:
 * {@code (0,0)} at the top-left vertex, {@code (1,1)} at the bottom-right.
 *
 * <p><b>Camera orientation.</b> The {@link PoseStack} supplied by
 * {@code RenderLevelStageEvent} is world-aligned (it carries only the
 * camera-position translation, not the camera rotation — the rotation lives
 * in the render-system model-view matrix). The quad is therefore built from
 * the live {@link Camera}'s world-space left and up vectors so that, after
 * the model-view rotation is applied by the shader, the quad faces the
 * camera.</p>
 *
 * <p><strong>Client-only class.</strong> Lives in the client render package
 * and must only be referenced from client-side rendering code.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see BulletRenderType
 * @see BulletRenderObject
 */
public final class BillboardRenderer {

    /** Half-size factor: the quad spans {@code scale} blocks (± scale &middot; 0.5). */
    public static final float HALF_SIZE_FACTOR = 0.5f;

    /** Full-brightness light value (block 240, sky 240) written to every vertex. */
    private static final int FULL_BRIGHTNESS = 15728880;

    /** No overlay (UV1) — the bullet has no hurt/damage flash. */
    private static final int NO_OVERLAY = 0;

    /** White identity tint (null-wire sentinel replaced at render time). */
    private static final Vector4f WHITE_TINT = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    private BillboardRenderer() {
    }

    /**
     * Renders a single bullet as a camera-facing quad.
     *
     * <p>Early-returns when the render object has no billboard texture, so
     * callers may safely invoke this for every bullet regardless of whether
     * a texture was assigned.</p>
     *
     * <p><b>Near-camera translucency</b> (系统七 §近相机距离透明度): the
     * {@code distanceAlpha} multiplier is folded into the tint's alpha
     * channel before drawing. The composed tint held by the render object is
     * never mutated — a fresh {@link Vector4f} is built when the fade is
     * active, so {@code onVisualTick} hooks and subsequent frames keep
     * reading the original values.</p>
     *
     * @param renderObject the bullet to render
     * @param poseStack    the camera-space pose stack, already translated to
     *                     the bullet's interpolated position
     * @param bufferSource the vertex buffer source for submitting geometry
     * @param partialTick  the frame partial tick (reserved for future
     *                     sub-frame animation; currently unused)
     * @param cameraPos    the camera world position. Orientation is read from
     *                     the live {@link Camera} instance obtained via
     *                     {@link Minecraft#gameRenderer} rather than from this
     *                     parameter; it is retained for API consistency with
     *                     {@link Model3DRenderer#render} (the dispatcher also
     *                     derives the distance fade from it).
     * @param distanceAlpha the distance-based opacity multiplier in
     *                     {@code [0.2, 1]} from
     *                     {@link DistanceAlphaCurve#computeAlpha}; multiply it
     *                     into the tint's alpha (design choice: the fade is
     *                     multiplicative, so an already-translucent tint stays
     *                     translucent on top of the distance fade)
     */
    public static void render(
            BulletRenderObject renderObject,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float partialTick,
            Vec3 cameraPos,
            float distanceAlpha) {
        ResourceLocation texture = renderObject.getTexture();
        if (texture == null) {
            return;
        }
        // Base draw: scale from renderObject, tint from composedTint
        // (null = white identity sentinel, 设计规格 §4.5).
        float halfSize = renderObject.getScale() * HALF_SIZE_FACTOR;
        Vector4f tint = renderObject.getComposedTint() != null
                ? renderObject.getComposedTint() : WHITE_TINT;
        if (distanceAlpha < 1.0F) {
            // Copy-on-fade: never write the fade back into the render object.
            tint = new Vector4f(tint.x, tint.y, tint.z, tint.w * distanceAlpha);
        }
        drawBillboard(texture, halfSize, tint, poseStack, bufferSource, partialTick, cameraPos);
    }

    /**
     * Draws a camera-facing quad with the given texture, half-size and tint.
     *
     * <p>Shared by the base bullet draw (via {@link #render}) and by each
     * {@code attach_layer} draw in
     * {@link BulletRenderDispatcher#renderByMode} — the dispatcher pushes its
     * own pose for offset/follow transforms before calling this method, so a
     * layer renders at the layer's own local origin (设计规格 §4.5).</p>
     *
     * @param texture      the billboard texture path, never {@code null}
     * @param halfSize     the quad half-size in blocks (already scale-applied)
     * @param tint         the vertex tint (each channel in [0,1]); the quad
     *                     multiplies the texture's colour by this tint
     * @param poseStack    the camera-space pose stack, already translated to
     *                     the draw origin (bullet position or layer offset)
     * @param bufferSource the vertex buffer source for submitting geometry
     * @param partialTick  the frame partial tick (reserved; unused)
     * @param cameraPos    the camera world position (reserved; unused)
     */
    public static void drawBillboard(
            ResourceLocation texture,
            float halfSize,
            Vector4f tint,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float partialTick,
            Vec3 cameraPos) {
        RenderType renderType = BulletRenderType.billboard(texture);
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vector3f look = camera.getLookVector();

        // right = -left (world-space). Precompute right * halfSize components.
        float rx = -left.x() * halfSize;
        float ry = -left.y() * halfSize;
        float rz = -left.z() * halfSize;
        // up * halfSize components
        float ux = up.x() * halfSize;
        float uy = up.y() * halfSize;
        float uz = up.z() * halfSize;

        // Normal faces the camera (opposite of the camera look direction).
        float nx = -look.x();
        float ny = -look.y();
        float nz = -look.z();

        // Clamp tint channels to [0,1] then convert to 0-255 vertex colour.
        int r = (int) (Math.max(0.0f, Math.min(1.0f, tint.x)) * 255.0f);
        int g = (int) (Math.max(0.0f, Math.min(1.0f, tint.y)) * 255.0f);
        int b = (int) (Math.max(0.0f, Math.min(1.0f, tint.z)) * 255.0f);
        int a = (int) (Math.max(0.0f, Math.min(1.0f, tint.w)) * 255.0f);

        PoseStack.Pose pose = poseStack.last();

        // Quad winding: bottom-left → bottom-right → top-right → top-left
        putVertex(vertexConsumer, pose, -rx - ux, -ry - uy, -rz - uz, 0.0f, 1.0f, nx, ny, nz, r, g, b, a);
        putVertex(vertexConsumer, pose,  rx - ux,  ry - uy,  rz - uz, 1.0f, 1.0f, nx, ny, nz, r, g, b, a);
        putVertex(vertexConsumer, pose,  rx + ux,  ry + uy,  rz + uz, 1.0f, 0.0f, nx, ny, nz, r, g, b, a);
        putVertex(vertexConsumer, pose, -rx + ux, -ry + uy, -rz + uz, 0.0f, 0.0f, nx, ny, nz, r, g, b, a);
    }

    /**
     * Writes a single {@link DefaultVertexFormat#NEW_ENTITY NEW_ENTITY}-format
     * vertex: position, color, texture UV, overlay, lightmap and normal.
     *
     * <p>Element order matches the NEW_ENTITY layout
     * (Position → Color → UV0 → UV1 → UV2 → Normal); the calls must appear
     * in this exact order so the vertex consumer fills the buffer
     * contiguously.</p>
     *
     * @param consumer the vertex consumer to write to
     * @param pose     the current pose for position and normal transformation
     * @param x        local-space x (relative to bullet center, world-aligned)
     * @param y        local-space y
     * @param z        local-space z
     * @param u        texture u coordinate
     * @param v        texture v coordinate
     * @param nx       world-space normal x (faces the camera)
     * @param ny       world-space normal y
     * @param nz       world-space normal z
     * @param r        vertex red channel (0-255)
     * @param g        vertex green channel (0-255)
     * @param b        vertex blue channel (0-255)
     * @param a        vertex alpha channel (0-255)
     */
    private static void putVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz,
            int r, int g, int b, int a) {
        consumer.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(NO_OVERLAY)
                .setLight(FULL_BRIGHTNESS)
                .setNormal(pose, nx, ny, nz);
    }
}
