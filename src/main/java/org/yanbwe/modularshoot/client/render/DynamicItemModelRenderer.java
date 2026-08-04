package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders a 2D item texture with the vanilla flat-item extrusion, driven
 * entirely by dynamic textures.
 *
 * <p>The design doc (§渲染器) mandates the vanilla item rendering pipeline
 * with automatic texture extrusion. Vanilla achieves this by baking
 * {@code item/generated} models into extruded quads at resource-load time —
 * a pipeline that cannot reference runtime-composited textures
 * ({@link DynamicGunTextureCache}). This class replicates the <em>visual
 * output</em> of that bake (设计文档 §枪械纹理: 原版物品渲染管线的立体化效果)
 * for a single dynamic texture: an extruded 16×16 block with its front face
 * at {@code z = 8.5}, back face at {@code z = 7.5} and four side faces,
 * mirroring the geometry produced by {@code ItemModelGenerator} for
 * {@code builtin/generated} models.</p>
 *
 * <p><b>Coordinate space:</b> quads are emitted in the baked-model space of
 * 0..1 (vanilla's {@code FaceBakery} divides block-element coordinates by 16
 * when baking, so an {@code ItemModelGenerator} element spanning
 * {@code (0,0,7.5)-(16,16,8.5)} becomes {@code (0,0,0.46875)-(1,1,0.53125)}).
 * Vertex {@code y} maps to texture {@code v} as {@code v = 1 - y}, keeping
 * the top of the texture at the top of the item.</p>
 *
 * <p><b>Transform handling:</b> {@code ItemRenderer.render} applies
 * {@code bakedModel.getTransforms().getTransform(context)} and then
 * {@code translate(-0.5, -0.5, -0.5)} <em>before</em> dispatching to a
 * custom renderer (NeoForge patch), so {@code renderByItem} receives a pose
 * already centred on the model origin. Flat vanilla items (e.g.
 * {@code item/generated} models without a {@code display} section) use
 * {@link ItemTransforms#NO_TRANSFORMS}, and so does this renderer — the
 * caller's pose plus the surrounding context (GUI slot, hand renderer, item
 * entity) is responsible for placement, exactly as with vanilla flat
 * items.</p>
 *
 * <p><b>Render type:</b> {@link RenderType#entityTranslucent} is used
 * because gun/plugin PNGs may contain semi-transparent pixels; the entity
 * translucent shader supports blending together with lightmap and overlay
 * input (packed via {@code setUv2}/{@code setUv1}). The render type is
 * memoized per texture location by vanilla, so repeated calls are cheap.</p>
 *
 * <p><b>Glint:</b> framework items are not enchantable, so no foil pass is
 * needed — mirroring {@code getFoilBuffer} with {@code hasFoil == false},
 * which simply returns the plain buffer.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see DynamicGunTextureCache
 * @see org.yanbwe.modularshoot.client.render.GunItemRenderer
 * @see org.yanbwe.modularshoot.client.render.PluginItemRenderer
 */
public final class DynamicItemModelRenderer {

    private DynamicItemModelRenderer() {
    }

    /**
     * Draws the extruded item quads for the given dynamic texture.
     *
     * <p>Emits six faces (front, back and four sides) as a single quad batch
     * to {@code bufferSource}, in baked-model space (0..1). The pose stack is
     * pushed and popped around the call, so the caller's stack is restored on
     * return. <em>No transform is applied here:</em> {@code renderByItem}
     * already receives a pose centred on the model origin (the vanilla
     * pipeline applies the model display transform and
     * {@code translate(-0.5, -0.5, -0.5)} before dispatching to a custom
     * renderer), and the {@link ItemTransforms#NO_TRANSFORMS} transform is
     * the identity.</p>
     *
     * @param texture      the registered dynamic texture location; must be
     *                     non-{@code null}
     * @param context      the display context (GUI, hand, ground, ...)
     * @param poseStack    the pose stack, already centred by the vanilla
     *                     pipeline
     * @param bufferSource the buffer source to submit quads to
     * @param light        the packed light value
     * @param overlay      the packed overlay value
     */
    public static void render(
            ResourceLocation texture,
            ItemDisplayContext context,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay) {

        poseStack.pushPose();

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
        PoseStack.Pose pose = poseStack.last();

        // Front face (z = 0.53125, normal +z), vertices CCW seen from +z.
        face(consumer, pose, light, overlay,
                0F, 0F, 0.53125F, 1F, 0F, 0.53125F, 1F, 1F, 0.53125F, 0F, 1F, 0.53125F,
                0F, 0F, 1F);
        // Back face (z = 0.46875, normal -z).
        face(consumer, pose, light, overlay,
                1F, 0F, 0.46875F, 0F, 0F, 0.46875F, 0F, 1F, 0.46875F, 1F, 1F, 0.46875F,
                0F, 0F, -1F);
        // Left side (x = 0, normal -x).
        face(consumer, pose, light, overlay,
                0F, 0F, 0.46875F, 0F, 0F, 0.53125F, 0F, 1F, 0.53125F, 0F, 1F, 0.46875F,
                -1F, 0F, 0F);
        // Right side (x = 1, normal +x).
        face(consumer, pose, light, overlay,
                1F, 0F, 0.53125F, 1F, 0F, 0.46875F, 1F, 1F, 0.46875F, 1F, 1F, 0.53125F,
                1F, 0F, 0F);
        // Bottom side (y = 0, normal -y).
        face(consumer, pose, light, overlay,
                0F, 0F, 0.46875F, 1F, 0F, 0.46875F, 1F, 0F, 0.53125F, 0F, 0F, 0.53125F,
                0F, -1F, 0F);
        // Top side (y = 1, normal +y).
        face(consumer, pose, light, overlay,
                0F, 1F, 0.53125F, 1F, 1F, 0.53125F, 1F, 1F, 0.46875F, 0F, 1F, 0.46875F,
                0F, 1F, 0F);

        poseStack.popPose();
    }

    /**
     * Renders the item with the vanilla missing model (purple-black square),
     * used when the item carries no framework data or its definition cannot
     * be resolved (e.g. on the main menu).
     *
     * <p>The missing model is a plain baked model with
     * {@code isCustomRenderer() == false}, so delegating to
     * {@code ItemRenderer.render} cannot recurse back into a custom
     * renderer.</p>
     *
     * @param stack        the item stack to render
     * @param context      the display context
     * @param poseStack    the pose stack
     * @param bufferSource the buffer source
     * @param light        the packed light value
     * @param overlay      the packed overlay value
     */
    public static void renderMissing(
            ItemStack stack,
            ItemDisplayContext context,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int light,
            int overlay) {

        var itemRenderer = Minecraft.getInstance().getItemRenderer();
        BakedModel missingModel = itemRenderer.getItemModelShaper().getModelManager().getMissingModel();
        itemRenderer.render(stack, context, false, poseStack, bufferSource, light, overlay, missingModel);
    }

    /**
     * Submits a single textured quad to the consumer.
     *
     * <p>Vertex order: {@code (x1,y1,z1)} {@code (x2,y2,z2)} {@code (x3,y3,z3)}
     * {@code (x4,y4,z4)}, all in baked-model space (0..1). The texture is
     * mapped with {@code u = x} and {@code v = 1 - y} so that the top of the
     * texture ({@code v = 0}) sits at the top of the item ({@code y = 1}),
     * matching the vanilla item sprite orientation. Each vertex is emitted
     * with white colour, the given packed overlay/light, and the face normal.</p>
     *
     * @param consumer the vertex consumer to write to
     * @param pose     the current pose for coordinate transformation
     * @param light    the packed light value
     * @param overlay  the packed overlay value
     * @param x1       first vertex x (baked model space)
     * @param y1       first vertex y
     * @param z1       first vertex z
     * @param x2       second vertex x
     * @param y2       second vertex y
     * @param z2       second vertex z
     * @param x3       third vertex x
     * @param y3       third vertex y
     * @param z3       third vertex z
     * @param x4       fourth vertex x
     * @param y4       fourth vertex y
     * @param z4       fourth vertex z
     * @param nx       face normal x
     * @param ny       face normal y
     * @param nz       face normal z
     */
    private static void face(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            int light,
            int overlay,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4,
            float nx, float ny, float nz) {

        vertex(consumer, pose, light, overlay, x1, y1, z1, 0F, 1F, nx, ny, nz);
        vertex(consumer, pose, light, overlay, x2, y2, z2, 1F, 1F, nx, ny, nz);
        vertex(consumer, pose, light, overlay, x3, y3, z3, 1F, 0F, nx, ny, nz);
        vertex(consumer, pose, light, overlay, x4, y4, z4, 0F, 0F, nx, ny, nz);
    }

    /**
     * Emits a single vertex into the consumer.
     *
     * @param consumer the vertex consumer to write to
     * @param pose     the current pose for coordinate transformation
     * @param light    the packed light value
     * @param overlay  the packed overlay value
     * @param x        vertex x (baked model space, 0..1)
     * @param y        vertex y (baked model space, 0..1)
     * @param z        vertex z (baked model space, 0..1)
     * @param u        texture u coordinate (0..1)
     * @param v        texture v coordinate (0..1)
     * @param nx       face normal x
     * @param ny       face normal y
     * @param nz       face normal z
     */
    private static void vertex(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            int light,
            int overlay,
            float x, float y, float z,
            float u, float v,
            float nx, float ny, float nz) {

        consumer.addVertex(pose, x, y, z)
                .setColor(1.0F, 1.0F, 1.0F, 1.0F)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
