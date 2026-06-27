package org.yanbwe.modularshoot.client.render;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Renders a bullet as a vanilla static JSON model, oriented along its flight
 * direction (设计文档 §渲染模式, line 1225; §渲染流程, line 1241).
 *
 * <p>This is the {@code 3d} render-mode pipeline. It loads a vanilla
 * {@link BakedModel} (the same format used by vanilla item/block models —
 * no bones, no animation, no custom model loader) through the vanilla
 * {@link ModelManager}, rotates it to align the model's local {@code +Z}
 * axis with {@link BulletRenderObject#getDirection()}, applies the bullet's
 * {@link BulletRenderObject#getScale() scale}, and draws it with the vanilla
 * {@link ModelBlockRenderer}.</p>
 *
 * <h2>Model loading</h2>
 * <p>The {@link BulletRenderObject#getModelLocation() model location} is a
 * {@link ResourceLocation} pointing at a vanilla JSON model. It is normalised
 * (stripping a leading {@code models/} and trailing {@code .json} so callers
 * may store either the asset path or the bare model id) and wrapped as an
 * <em>inventory</em>-variant {@link ModelResourceLocation}, then resolved via
 * {@link ModelManager#getModel}. When the model is absent the manager returns
 * its built-in missing model (the black-and-purple cube), so rendering never
 * crashes.</p>
 *
 * <p><b>Registration requirement (E-02):</b> a standalone JSON model is only
 * present in the model manager's baked registry if something references it.
 * Bullet models must therefore be registered for baking separately — e.g.
 * via NeoForge's additional-model registration
 * ({@code ModelEvent.RegisterAdditional}).</p>
 *
 * <p>This renderer intentionally does <strong>not</strong> perform that
 * registration. There is a fundamental timing constraint:
 * {@code ModelEvent.RegisterAdditional} fires during resource reload (on a
 * worker thread), before datapack-driven registries such as
 * {@code modularshoot:guns} are populated on the client. The gun definitions
 * — and therefore the bullet model paths — are simply not available at
 * registration time. Additionally, {@code RegisterAdditional} requires the
 * {@code STANDALONE_VARIANT} variant, while this renderer looks up models
 * via the {@code inventory} variant, making the two incompatible without a
 * dual-registration scheme.</p>
 *
 * <p>Instead, this renderer adopts a <em>deferred-loading</em> strategy:
 * {@link ModelManager#getModel} returns the built-in missing model (the
 * black-and-purple cube) for unregistered locations, so rendering never
 * crashes. When a bullet model has not been pre-registered the player sees
 * the missing-model pattern rather than the intended projectile — a safe,
 * non-fatal degradation. Model registration will be handled in a future
 * milestone via a resource-convention scan (registering all JSON files under
 * a conventional path such as {@code assets/<namespace>/models/bullet/}).
 * See 设计文档 §模型 for the documented rationale.</p>
 *
 * <h2>Direction rotation</h2>
 * <p>The model's forward axis is assumed to be local {@code +Z}. Two Euler
 * rotations align it with the (normalised) flight direction
 * {@code d = (dx, dy, dz)}:</p>
 * <ul>
 *   <li>{@code yaw = atan2(dx, dz)} — rotation about {@code +Y} to point the
 *       model toward the horizontal projection of {@code d}.</li>
 *   <li>{@code pitch = atan2(-dy, sqrt(dx*dx + dz*dz))} — rotation about
 *       {@code +X} to tilt the model up/down. The sign is negative because a
 *       positive rotation about {@code +X} sends {@code +Z} toward {@code -Y}
 *       (right-hand rule), so an upward-pointing bullet needs a negative
 *       angle.</li>
 * </ul>
 * <p>Applied in {@link PoseStack} order (scale → yaw → pitch → center) the
 * vertex receives: center, then pitch, then yaw, then scale — yielding exact
 * alignment of local {@code +Z} to {@code d}.</p>
 *
 * <h2>Caching</h2>
 * <p>Resolved {@link BakedModel}s are cached in a static map keyed by the raw
 * model location to avoid re-resolving every frame. Resource reloads are
 * detected automatically: the model manager's {@code missingModel} reference
 * changes on each reload, so when the cached sentinel no longer matches the
 * current one the cache is invalidated — no event listener required.</p>
 *
 * <p><strong>Client-only class.</strong> Referenced only from
 * {@link BulletRenderDispatcher} which runs on the client render thread.</p>
 *
 * @see BulletRenderDispatcher
 * @see BulletRenderObject
 */
public final class Model3DRenderer {

    /**
     * Render type for bullet models — vanilla block cutout. Uses the block
     * atlas ({@code BLOCK_SHEET}) and the {@code BLOCK} vertex format, which
     * matches what {@link ModelBlockRenderer#renderModel} emits via
     * {@code putBulkData}. The default output target (main render target)
     * keeps geometry visible in both fabulous and non-fabulous graphics,
     * avoiding the translucent-target compositing timing issues that
     * {@link RenderType#translucent()} would hit in the
     * {@code AFTER_PARTICLES} stage.
     */
    private static final RenderType RENDER_TYPE = RenderType.cutout();

    /** Full-bright fallback when the client level is unavailable (e.g. main menu). */
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    /** Cached baked models keyed by the raw model location from {@link BulletRenderObject}. */
    private static final Map<ResourceLocation, BakedModel> MODEL_CACHE = new HashMap<>();

    /** Reload sentinel: the missing-model reference changes on each resource reload. */
    @Nullable private static BakedModel cachedMissingModel;

    private Model3DRenderer() {
    }

    /**
     * Renders a single bullet as a vanilla static JSON model.
     *
     * <p>The {@code poseStack} is expected to already be translated to the
     * bullet's interpolated camera-space position by
     * {@link BulletRenderDispatcher}; this method only applies orientation,
     * scale and centering transforms before drawing. {@code partialTick} and
     * {@code cameraPos} are accepted for pipeline symmetry with the billboard
     * renderer but are not used here.</p>
     *
     * @param renderObject  the bullet to render
     * @param poseStack     the camera-space pose stack, already translated to
     *                      the bullet position
     * @param bufferSource  the vertex buffer source for submitting geometry
     * @param partialTick   the frame partial tick (unused, reserved for parity)
     * @param cameraPos     the camera world position (unused, reserved for parity)
     */
    public static void render(
            BulletRenderObject renderObject,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            float partialTick,
            Vec3 cameraPos) {
        ResourceLocation modelLocation = renderObject.getModelLocation();
        if (modelLocation == null) {
            return;
        }

        BakedModel bakedModel = getOrLoadModel(modelLocation);
        if (bakedModel == null) {
            return;
        }

        poseStack.pushPose();
        applyTransforms(poseStack, renderObject.getDirection(), renderObject.getScale());
        drawModel(bakedModel, poseStack, bufferSource, computeLight(renderObject.getPosition()));
        poseStack.popPose();
    }

    /**
     * Resolves a baked model for the given location, using the cache when
     * possible and invalidating it automatically on resource reload.
     *
     * @param modelLocation the raw model location from the render object
     * @return the resolved baked model (never {@code null} — the model manager
     *         returns its missing model for unknown locations)
     */
    private static BakedModel getOrLoadModel(ResourceLocation modelLocation) {
        ModelManager modelManager = Minecraft.getInstance().getModelManager();
        invalidateCacheOnReload(modelManager);
        return MODEL_CACHE.computeIfAbsent(modelLocation, Model3DRenderer::loadFromManager);
    }

    /**
     * Clears the model cache when a resource reload is detected by comparing
     * the model manager's missing-model reference against the cached sentinel.
     */
    private static void invalidateCacheOnReload(ModelManager modelManager) {
        BakedModel currentMissing = modelManager.getMissingModel();
        if (cachedMissingModel != currentMissing) {
            MODEL_CACHE.clear();
            cachedMissingModel = currentMissing;
        }
    }

    /**
     * Loads a baked model from the vanilla model manager, normalising the
     * location to an inventory-variant {@link ModelResourceLocation}.
     *
     * @param rawLocation the raw model location
     * @return the baked model (missing model if not registered for baking)
     */
    private static BakedModel loadFromManager(ResourceLocation rawLocation) {
        ModelResourceLocation resourceLocation = toModelResourceLocation(rawLocation);
        return Minecraft.getInstance().getModelManager().getModel(resourceLocation);
    }

    /**
     * Normalises a raw model location to a standalone-variant
     * {@link ModelResourceLocation}, tolerating either the asset path
     * ({@code models/bullet/bullet.json}) or the bare model id
     * ({@code bullet/bullet}).
     *
     * <p>Uses the {@code standalone} variant because
     * {@code ModelEvent.RegisterAdditional} requires that variant for
     * side-loaded models (NeoForge 1.21.1). The model is registered with
     * {@link ModelResourceLocation#standalone(ResourceLocation)} during
     * resource reload and must be looked up with the same variant.</p>
     *
     * @param raw the raw model location
     * @return the standalone-variant model resource location
     */
    private static ModelResourceLocation toModelResourceLocation(ResourceLocation raw) {
        String path = raw.getPath();
        if (path.startsWith("models/")) {
            path = path.substring("models/".length());
        }
        if (path.endsWith(".json")) {
            path = path.substring(0, path.length() - ".json".length());
        }
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(raw.getNamespace(), path));
    }

    /**
     * Applies scale, direction rotation and model centering to the pose stack.
     *
     * <p>Vertex-side order (innermost first): center {@code (-0.5)} → pitch
     * → yaw → scale, which aligns the model's local {@code +Z} with the
     * flight direction.</p>
     *
     * @param poseStack the pose stack to transform
     * @param direction the bullet flight direction (need not be normalised)
     * @param scale     the visual scale multiplier
     */
    private static void applyTransforms(PoseStack poseStack, Vec3 direction, float scale) {
        poseStack.scale(scale, scale, scale);
        applyDirectionRotation(poseStack, direction);
        // Vanilla models live in a [0,1] cube with the origin at a corner;
        // centre the model on the bullet position before drawing.
        poseStack.translate(-0.5F, -0.5F, -0.5F);
    }

    /**
     * Rotates the pose stack so the model's local {@code +Z} axis points
     * along the flight direction.
     *
     * @param poseStack the pose stack to rotate
     * @param direction the bullet flight direction (need not be normalised)
     */
    private static void applyDirectionRotation(PoseStack poseStack, Vec3 direction) {
        double dx = direction.x;
        double dy = direction.y;
        double dz = direction.z;
        float horizontalLength = (float) Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.atan2(dx, dz);
        float pitch = (float) Math.atan2(-dy, horizontalLength);

        // PoseStack post-multiplies, so the second call is applied first to
        // the vertex: yaw (about +Y) then pitch (about +X).
        poseStack.mulPose(Axis.YP.rotation(yaw));
        poseStack.mulPose(Axis.XP.rotation(pitch));
    }

    /**
     * Computes the packed light at the bullet's world position, falling back
     * to full-bright when the client level is unavailable.
     *
     * @param worldPos the bullet's world position
     * @return the packed light value
     */
    private static int computeLight(Vec3 worldPos) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return FULL_BRIGHT;
        }
        return LevelRenderer.getLightColor(level, BlockPos.containing(worldPos.x, worldPos.y, worldPos.z));
    }

    /**
     * Draws a baked model through the vanilla {@link ModelBlockRenderer}.
     *
     * @param bakedModel   the model to draw
     * @param poseStack    the pose stack (already transformed)
     * @param bufferSource the buffer source
     * @param light        the packed light value
     */
    private static void drawModel(
            BakedModel bakedModel,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light) {
        ModelBlockRenderer modelRenderer = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RENDER_TYPE);
        modelRenderer.renderModel(
                poseStack.last(),
                vertexConsumer,
                null,
                bakedModel,
                1.0F, 1.0F, 1.0F,
                light,
                OverlayTexture.NO_OVERLAY);
    }

    /**
     * Clears the baked-model cache. Public so an explicit reload hook can
     * force invalidation if ever needed; under normal operation the
     * automatic reload sentinel makes manual calls unnecessary.
     */
    public static void clearCache() {
        MODEL_CACHE.clear();
        cachedMissingModel = null;
    }
}
