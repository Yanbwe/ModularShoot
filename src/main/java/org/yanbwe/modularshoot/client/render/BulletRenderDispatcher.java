package org.yanbwe.modularshoot.client.render;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector4f;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.client.config.ModularShootClientConfig;
import org.yanbwe.modularshoot.network.ClientBulletSnapshot;

/**
 * Client-side render dispatcher that draws all in-flight bullets every frame
 * during {@link RenderLevelStageEvent} (设计文档 §渲染流程, line 1239).
 *
 * <p>Listens to the {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} stage,
 * which fires after entities and particles have been rendered and runs inside
 * the fabulous transparency target — ideal for bullet sprites and small 3d
 * models that should blend correctly with the world (设计文档 §渲染流程).</p>
 *
 * <p>Each frame the dispatcher:</p>
 * <ol>
 *   <li>Fetches all active {@link BulletRenderObject}s from
 *       {@link BulletRenderManager#getAllRenderObjects()}</li>
 *   <li>Fires {@link VisualTickHookDispatcher#dispatchVisualTick} for each
 *       bullet so registered trait hooks can mutate appearance in-flight
 *       (设计文档 §特性视觉钩子)</li>
 *   <li>Interpolates the bullet position between
 *       {@link BulletRenderObject#getPrevPosition()} and
 *       {@link BulletRenderObject#getPosition()} using a <em>time-based</em>
 *       factor — {@link BulletRenderObject#getInterpolationFactor(long)} —
 *       which grows from the last sync-packet arrival and saturates at 1
 *       (回弹修复; the pair is advanced by the server-tick clock, so the
 *       client-tick {@code partialTick} must not drive the lerp, see
 *       {@link RenderInterpolation#interpolationFactor})
 *       (设计文档 §位置插值, line 1242)</li>
 *   <li>Sorts the objects <em>back-to-front</em> by camera distance before
 *       drawing. The billboard RenderType does not write depth (a square
 *       sprite's fully-transparent corners must not occlude bullets behind
 *       it), so alpha blending — whose order is submission order — resolves
 *       which bullet draws in front of another: farthest first, nearer
 *       bullets overwrite them with their opaque spheres (透明遮挡修复)</li>
 *   <li>Dispatches to the appropriate renderer based on
 *       {@link BulletRenderObject#getRenderMode()}:
 *       <ul>
 *         <li>{@code billboard} → {@link BillboardRenderer} — builds a
 *             camera-facing quad, binds a custom RenderType (depth test,
 *             no depth write, alpha blend, no lighting), samples the texture
 *             (设计文档 §渲染流程, line 1240)</li>
 *         <li>{@code 3d} → Model3DRenderer (子任务 10, TODO)</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Camera-space rendering:</b> the {@link PoseStack} provided by the event
 * is already in camera/view space, so each bullet is translated by
 * {@code interpolatedPosition - cameraPosition} before drawing. This mirrors
 * how vanilla particles and the NeoForge debug renderers offset world-space
 * geometry into the camera-origin frame.</p>
 *
 * <p><strong>Client-only class.</strong> Registered on the NeoForge event bus
 * with {@link Dist#CLIENT}; never loaded on dedicated servers.</p>
 *
 * @see BulletRenderManager
 * @see BulletRenderObject
 * @see VisualTickHookDispatcher
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class BulletRenderDispatcher {

    /** Render stage used for bullet drawing — after particles, inside fabulous target. */
    private static final RenderLevelStageEvent.Stage RENDER_STAGE =
            RenderLevelStageEvent.Stage.AFTER_PARTICLES;

    /** White identity tint (null-wire sentinel replaced at render time). */
    private static final Vector4f WHITE_TINT = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    /**
     * Reusable per-frame sort buffer (审查优化 P6): the render thread is
     * single-threaded and the frame loop is strictly sequential, so one
     * shared list avoids the per-frame stream pipeline + list allocation of
     * {@code stream().sorted().toList()} while keeping the same stable
     * TimSort ordering. Callers must not retain the returned list beyond the
     * current frame.
     */
    private static final List<BulletRenderObject> SORT_BUFFER = new java.util.ArrayList<>();

    private BulletRenderDispatcher() {
    }

    /**
     * Renders all in-flight bullets during the
     * {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} stage.
     *
     * <p>Early-returns for any other stage so the listener is a no-op outside
     * the target stage (the event fires once per stage per frame).</p>
     *
     * @param event the render-level-stage event
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RENDER_STAGE) {
            return;
        }
        renderAllBullets(event);
    }

    /**
     * Iterates every active {@link BulletRenderObject}, fires visual-tick
     * hooks, and dispatches to the mode-specific renderer.
     *
     * <p>Short-circuits when there are no render objects to avoid fetching the
     * buffer source or camera on empty frames.</p>
     *
     * @param event the render-level-stage event carrying the PoseStack, camera
     *              and partial-tick data
     */
    private static void renderAllBullets(RenderLevelStageEvent event) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        BulletRenderManager renderManager = BulletManager.getClientLevel(level);
        Collection<BulletRenderObject> renderObjects = renderManager.getAllRenderObjects();
        if (renderObjects.isEmpty()) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        // One wall-clock read per frame: every bullet's interpolation factor
        // uses the same "now" so the frame is internally consistent (回弹修复).
        long nowMillis = System.currentTimeMillis();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        // Near-camera translucency (系统七 §近相机距离透明度): read the
        // client config once per frame — edits made in the in-game config
        // screen take effect on the next frame. When disabled, distanceAlpha
        // stays 1.0 for every bullet and the fade is a no-op.
        boolean nearTranslucency = ModularShootClientConfig.isNearTranslucencyEnabled();
        double fadeDistance = ModularShootClientConfig.getFadeDistance();
        float minOpacity = ModularShootClientConfig.getMinOpacity();

        // Back-to-front ordering (透明遮挡修复): the billboard RenderType no
        // longer writes depth, so alpha blending resolves bullet-over-bullet
        // occlusion — and blending order is submission order. Farthest bullets
        // must be drawn first so nearer opaque spheres overwrite them.
        List<BulletRenderObject> ordered = sortBackToFront(renderObjects, cameraPos);

        for (BulletRenderObject renderObject : ordered) {
            // Fire visual-tick hooks so traits can mutate appearance before draw.
            // The snapshot supplies frozen stats/traits so hooks can make
            // data-driven visual decisions (设计文档 §特性视觉钩子, line 1298).
            ClientBulletSnapshot snapshot = renderManager.getSnapshot(renderObject.getBulletId());
            VisualTickHookDispatcher.dispatchVisualTick(snapshot, renderObject);

            Vec3 interpolatedPos = interpolatePosition(renderObject, nowMillis);
            // Distance fade is computed from the interpolated position (more
            // accurate than the raw tick position at high bullet speeds).
            float distanceAlpha = 1.0F;
            if (nearTranslucency) {
                distanceAlpha = DistanceAlphaCurve.computeAlpha(
                        interpolatedPos.distanceToSqr(cameraPos), fadeDistance, minOpacity);
            }
            renderByMode(renderObject, poseStack, bufferSource, partialTick, cameraPos,
                    interpolatedPos, distanceAlpha);
        }
    }

    /**
     * Orders render objects by descending camera distance (farthest first).
     *
     * <p>The billboard RenderType writes no depth — a square sprite's
     * fully-transparent corners must not occlude bullets behind it — so the
     * depth test can no longer decide which bullet draws in front of another;
     * alpha blending does, and blending order is submission order (透明遮挡修复).
     * Farthest-first submission makes a nearer bullet's opaque sphere
     * (alpha = 1) correctly overwrite the farther bullet while its transparent
     * corners let it show through.</p>
     *
     * <p>The sort key is the bullet's raw tick position
     * ({@link BulletRenderObject#getPosition()}) rather than the interpolated
     * one: interpolation happens per-draw, and the pair {@code prevPosition →
     * position} spans less than one sync segment — a distance far below the
     * overlap scale this ordering resolves. The sort is stable, so bullets at
     * an equal distance keep the manager's insertion order and the draw
     * sequence is deterministic.</p>
     *
     * @param renderObjects the active render objects, in manager iteration order
     * @param cameraPos     the camera world position
     * @return the same objects ordered farthest-to-nearest
     */
    static List<BulletRenderObject> sortBackToFront(
            Collection<BulletRenderObject> renderObjects, Vec3 cameraPos) {
        SORT_BUFFER.clear();
        SORT_BUFFER.addAll(renderObjects);
        SORT_BUFFER.sort(Comparator.comparingDouble(
                (BulletRenderObject obj) -> obj.getPosition().distanceToSqr(cameraPos)).reversed());
        return SORT_BUFFER;
    }

    /**
     * Linearly interpolates between the bullet's previous and current position
     * using the <em>time-based</em> interpolation factor (回弹修复, 设计文档
     * §位置插值, line 1242).
     *
     * <p>The factor comes from {@link BulletRenderObject#getInterpolationFactor(long)}
     * — wall-clock time since the pair was last advanced by a sync packet,
     * saturating at 1 — rather than the client-tick {@code partialTick}. The
     * pair is advanced on the server-tick clock; a client tick that processes
     * zero packets would reset {@code partialTick} and replay the stale pair
     * from 0, visibly bouncing the bullet backward one server step. The
     * time-based factor only grows, so a stale pair holds the bullet at its
     * current position instead.</p>
     *
     * @param renderObject the bullet to interpolate
     * @param nowMillis    the frame's wall-clock time (shared by every bullet
     *                     of the frame, read once in {@link #renderAllBullets})
     * @return the interpolated world position
     */
    private static Vec3 interpolatePosition(BulletRenderObject renderObject, long nowMillis) {
        float factor = renderObject.getInterpolationFactor(nowMillis);
        return RenderInterpolation.lerpPosition(
                renderObject.getPrevPosition(), renderObject.getPosition(), factor);
    }

    /**
     * Dispatches the render object to the renderer matching its
     * {@link BulletRenderObject#getRenderMode() render mode}.
     *
     * <p>Each call translates the PoseStack from camera space to the bullet's
     * world position (via {@code interpolatedPos - cameraPos}) before invoking
     * the mode-specific renderer, then restores the stack. The push/pop pair
     * guarantees each bullet's transform is isolated.</p>
     *
     * <p>The {@code billboard} branch delegates to {@link BillboardRenderer},
     * which builds a camera-facing quad, binds a custom RenderType (depth
     * test, alpha blend, no lighting), and samples the texture
     * (设计文档 §渲染流程, line 1240).</p>
     * <p>The {@code 3d} branch delegates to {@link Model3DRenderer}, which
     * loads a vanilla static JSON model, rotates it to align with the bullet's
     * flight direction, and draws it (设计文档 §渲染流程, line 1241).</p>
     *
     * @param renderObject    the bullet to render
     * @param poseStack       the camera-space pose stack
     * @param bufferSource    the vertex buffer source for submitting geometry
     * @param partialTick     the frame partial tick (passed to renderers for
     *                        any sub-frame animation)
     * @param cameraPos       the camera world position (for billboard orientation)
     * @param interpolatedPos the bullet's interpolated world position
     * @param distanceAlpha   the distance-based opacity multiplier in
     *                        {@code [0.2, 1]} (系统七 §近相机距离透明度);
     *                        applied to the base draw and every attach_layer
     *                        draw, never written back to the render object
     */
    private static void renderByMode(
            BulletRenderObject renderObject,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float partialTick,
            Vec3 cameraPos,
            Vec3 interpolatedPos,
            float distanceAlpha) {
        String renderMode = renderObject.getRenderMode();
        // Camera-space offset: translate from camera origin to bullet world pos
        double offsetX = interpolatedPos.x - cameraPos.x;
        double offsetY = interpolatedPos.y - cameraPos.y;
        double offsetZ = interpolatedPos.z - cameraPos.z;

        poseStack.pushPose();
        poseStack.translate(offsetX, offsetY, offsetZ);

        // 1. Base draw: renderScale × composedTint applied by the renderers.
        if (BulletRenderObject.RENDER_MODE_BILLBOARD.equals(renderMode)) {
            BillboardRenderer.render(renderObject, poseStack, bufferSource, partialTick, cameraPos, distanceAlpha);
        } else if (BulletRenderObject.RENDER_MODE_3D.equals(renderMode)) {
            Model3DRenderer.render(renderObject, poseStack, bufferSource, partialTick,
                    interpolatedPos, distanceAlpha);
        }

        // 2. attach_layer draws: each layer pushes its own pose per follow
        // params (设计规格 §4.5). v1 supports billboard layers fully; a 3d
        // layer is a no-op with a DEBUG log (explicit scope decision — see
        // the task-10 note in the implementation plan).
        for (BulletRenderObject.LayerData layer : renderObject.getLayers()) {
            poseStack.pushPose();
            // offset relative to the base center
            poseStack.translate(layer.offsetX(), layer.offsetY(), layer.offsetZ());
            // follow_scale: inherit the base renderScale; layer.scale on top
            float effectiveScale = layer.followScale()
                    ? renderObject.getScale() * layer.scale() : layer.scale();
            // tint: null = white identity sentinel; copy-on-fade so the
            // shared record value is never mutated
            Vector4f layerTint = layer.tint() != null
                    ? layer.tint() : WHITE_TINT;
            if (distanceAlpha < 1.0F) {
                layerTint = new Vector4f(layerTint.x, layerTint.y, layerTint.z,
                        layerTint.w * distanceAlpha);
            }
            if (BulletRenderObject.RENDER_MODE_BILLBOARD.equals(layer.renderMode())
                    && layer.texture() != null) {
                BillboardRenderer.drawBillboard(
                        layer.texture(),
                        effectiveScale * BillboardRenderer.HALF_SIZE_FACTOR,
                        layerTint,
                        poseStack, bufferSource, partialTick, cameraPos);
            } else if (BulletRenderObject.RENDER_MODE_3D.equals(layer.renderMode())) {
                // v1 scope: 3d attach_layer is not drawn (documented
                // limitation; billboard layers cover the common
                // trail/aura use cases).
                ModularShoot.LOGGER.debug(
                        "3d attach_layer skipped in v1 (layer model={})", layer.model());
            }
            poseStack.popPose();
        }

        poseStack.popPose();
    }
}
