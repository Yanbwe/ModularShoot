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

/**
 * Renders a billboard (camera-facing quad) for a single in-flight bullet
 * (设计文档 §渲染流程, line 1240).
 *
 * <p>Constructs a 4-vertex quad oriented to face the camera using the
 * camera's right and up vectors, then submits it through the custom
 * {@link BulletRenderType billboard RenderType} that enables depth test,
 * translucent blending, no lightmap and no cull. The quad is centered on
 * the bullet's interpolated position — the {@link PoseStack} is already
 * translated there by {@link BulletRenderDispatcher} — and scaled by
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
    private static final float HALF_SIZE_FACTOR = 0.5f;

    /** Full-brightness light value (block 240, sky 240) written to every vertex. */
    private static final int FULL_BRIGHTNESS = 15728880;

    /** White, fully opaque vertex color — lets the texture supply all color. */
    private static final int COLOR_R = 255;
    private static final int COLOR_G = 255;
    private static final int COLOR_B = 255;
    private static final int COLOR_A = 255;

    /** No overlay (UV1) — the bullet has no hurt/damage flash. */
    private static final int NO_OVERLAY = 0;

    private BillboardRenderer() {
    }

    /**
     * Renders a single bullet as a camera-facing quad.
     *
     * <p>Early-returns when the render object has no billboard texture, so
     * callers may safely invoke this for every bullet regardless of whether
     * a texture was assigned.</p>
     *
     * @param renderObject the bullet to render
     * @param poseStack    the camera-space pose stack, already translated to
     *                     the bullet's interpolated position
     * @param bufferSource the vertex buffer source for submitting geometry
     * @param partialTick  the frame partial tick (reserved for future
     *                     sub-frame animation; currently unused)
     * @param cameraPos    the camera world position (reserved for future
     *                     distance-based effects; orientation is read from
     *                     the live {@link Camera} instance)
     */
    public static void render(
            BulletRenderObject renderObject,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float partialTick,
            Vec3 cameraPos) {
        ResourceLocation texture = renderObject.getTexture();
        if (texture == null) {
            return;
        }

        RenderType renderType = BulletRenderType.billboard(texture);
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        Vector3f look = camera.getLookVector();

        float halfSize = renderObject.getScale() * HALF_SIZE_FACTOR;

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

        PoseStack.Pose pose = poseStack.last();

        // Quad winding: bottom-left → bottom-right → top-right → top-left
        putVertex(vertexConsumer, pose, -rx - ux, -ry - uy, -rz - uz, 0.0f, 1.0f, nx, ny, nz);
        putVertex(vertexConsumer, pose,  rx - ux,  ry - uy,  rz - uz, 1.0f, 1.0f, nx, ny, nz);
        putVertex(vertexConsumer, pose,  rx + ux,  ry + uy,  rz + uz, 1.0f, 0.0f, nx, ny, nz);
        putVertex(vertexConsumer, pose, -rx + ux, -ry + uy, -rz + uz, 0.0f, 0.0f, nx, ny, nz);
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
     */
    private static void putVertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z)
                .setColor(COLOR_R, COLOR_G, COLOR_B, COLOR_A)
                .setUv(u, v)
                .setOverlay(NO_OVERLAY)
                .setLight(FULL_BRIGHTNESS)
                .setNormal(pose, nx, ny, nz);
    }
}
