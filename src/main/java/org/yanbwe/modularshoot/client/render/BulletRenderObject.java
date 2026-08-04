package org.yanbwe.modularshoot.client.render;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

/**
 * Pure client-side render data object for a single in-flight bullet.
 *
 * <p>This is <b>not</b> a Minecraft {@code Entity}. It is never registered
 * with {@code EntityType}, never enters {@code level.getEntities()}, and
 * participates in none of the vanilla entity traversal or logic pipelines.
 * This upholds the core design principle that "bullets are not implemented as
 * Minecraft entities" and avoids the client-side entity-iteration cost that
 * hundreds of per-shot entities would impose (设计文档 §渲染对象与渲染管理器).</p>
 *
 * <p>Lifecycle is driven entirely by network sync: the client-side
 * {@code BulletRenderManager} creates, updates and destroys instances in
 * response to {@code BulletS2CPacket} messages. Once created, the
 * {@code bulletId} is immutable and correlates 1:1 with the server-side
 * {@link org.yanbwe.modularshoot.bullet.BulletRecord#getBulletId()}.</p>
 *
 * <p>The visual fields ({@code position}, {@code prevPosition},
 * {@code direction}, {@code texture}, {@code modelLocation},
 * {@code renderMode}, {@code scale}) are intentionally mutable so that the
 * render manager and visual hooks ({@code onVisualTick}) can adjust a
 * bullet's appearance in-flight — e.g. swapping texture, scaling on
 * proximity, or switching between billboard and 3d modes (设计文档
 * §客户端视觉同步与渲染).</p>
 *
 * <p>Position interpolation: {@link #prevPosition} lags one tick behind
 * {@link #position} and is used by the renderer to smooth high-speed bullets
 * across frames via {@code partialTick}. Use {@link #updatePosition(Vec3)}
 * for the common case of advancing the bullet each sync tick; it atomically
 * archives the old position before applying the new one. Direct
 * {@link #setPosition(Vec3)} is available for callers that manage
 * {@code prevPosition} themselves (设计文档 §位置插值).</p>
 *
 * @see BulletStyle.RenderMode
 */
public final class BulletRenderObject {

    /** Render-mode tag for the billboard pipeline. */
    public static final String RENDER_MODE_BILLBOARD = BulletStyle.RenderMode.BILLBOARD.getSerializedName();

    /** Render-mode tag for the 3d model pipeline. */
    public static final String RENDER_MODE_3D = BulletStyle.RenderMode.THREE_D.getSerializedName();

    private final int bulletId;
    private Vec3 position;
    private Vec3 prevPosition;
    private Vec3 direction;
    @Nullable private ResourceLocation texture;
    @Nullable private ResourceLocation modelLocation;
    private String renderMode;
    private float scale;
    /**
     * Composed tint (channel-wise product of all TintModifiers), or
     * {@code null} as the client-side sentinel for the white identity
     * {@code (1,1,1,1)} (设计规格 §4.5 — the wire already collapses white to
     * a null sentinel; the renderer rebuilds white when null).
     */
    @Nullable private Vector4f composedTint;
    /** Additive attach_layer data in source order; empty when none. */
    private List<LayerData> layers = List.of();

    /**
     * @param bulletId      unique-per-dimension bullet id, matching the server BulletRecord
     * @param position      initial world position
     * @param direction     initial flight direction (normalized)
     * @param texture       billboard-mode texture path, or {@code null} when not used
     * @param modelLocation 3d-mode model path, or {@code null} when not used
     * @param renderMode    rendering pipeline tag — {@link #RENDER_MODE_BILLBOARD} or {@link #RENDER_MODE_3D}
     * @param scale         visual scale multiplier applied at draw time
     */
    public BulletRenderObject(
            int bulletId,
            Vec3 position,
            Vec3 direction,
            @Nullable ResourceLocation texture,
            @Nullable ResourceLocation modelLocation,
            String renderMode,
            float scale,
            @Nullable Vector4f composedTint,
            List<LayerData> layers) {
        this.bulletId = bulletId;
        this.position = position;
        this.prevPosition = position;
        this.direction = direction;
        this.texture = texture;
        this.modelLocation = modelLocation;
        this.renderMode = renderMode;
        this.scale = scale;
        this.composedTint = composedTint;
        this.layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /** Returns the immutable bullet id correlating with the server BulletRecord. */
    public int getBulletId() {
        return bulletId;
    }

    /** Returns the current world position. */
    public Vec3 getPosition() {
        return position;
    }

    /**
     * Replaces the current position without touching {@code prevPosition}.
     *
     * <p>Prefer {@link #updatePosition(Vec3)} unless you intentionally manage
     * the previous-position slot yourself (e.g. replaying a known trajectory).</p>
     */
    public void setPosition(Vec3 position) {
        this.position = position;
    }

    /** Returns the previous-tick position used for frame interpolation. */
    public Vec3 getPrevPosition() {
        return prevPosition;
    }

    /** Directly replaces the previous-tick position slot. */
    public void setPrevPosition(Vec3 prevPosition) {
        this.prevPosition = prevPosition;
    }

    /**
     * Advances the bullet in one atomic step: archives the current position
     * as {@code prevPosition}, then stores the new position.
     *
     * <p>This is the canonical way to move a render object each sync tick —
     * it guarantees the interpolation pair stays consistent without requiring
     * the caller to remember the two-step dance (设计文档 §位置插值).</p>
     */
    public void updatePosition(Vec3 newPosition) {
        this.prevPosition = this.position;
        this.position = newPosition;
    }

    /** Returns the current flight direction. */
    public Vec3 getDirection() {
        return direction;
    }

    /** Replaces the flight direction. */
    public void setDirection(Vec3 direction) {
        this.direction = direction;
    }

    /** Returns the billboard-mode texture path, or {@code null} when unused. */
    @Nullable
    public ResourceLocation getTexture() {
        return texture;
    }

    /** Sets the billboard-mode texture path; only consulted when {@code renderMode} is billboard. */
    public void setTexture(@Nullable ResourceLocation texture) {
        this.texture = texture;
    }

    /** Returns the 3d-mode model path, or {@code null} when unused. */
    @Nullable
    public ResourceLocation getModelLocation() {
        return modelLocation;
    }

    /** Sets the 3d-mode model path; only consulted when {@code renderMode} is 3d. */
    public void setModelLocation(@Nullable ResourceLocation modelLocation) {
        this.modelLocation = modelLocation;
    }

    /** Returns the current render-mode tag ({@link #RENDER_MODE_BILLBOARD} or {@link #RENDER_MODE_3D}). */
    public String getRenderMode() {
        return renderMode;
    }

    /** Switches the render pipeline at runtime — {@link #RENDER_MODE_BILLBOARD} or {@link #RENDER_MODE_3D}. */
    public void setRenderMode(String renderMode) {
        this.renderMode = renderMode;
    }

    /** Returns the visual scale multiplier. */
    public float getScale() {
        return scale;
    }

    /** Sets the visual scale multiplier applied at draw time. */
    public void setScale(float scale) {
        this.scale = scale;
    }

    /**
     * Returns the composed tint, or {@code null} as the white-identity
     * sentinel (设计规格 §4.5). Renderers multiply their vertex colour by this
     * tint; {@code null} behaves as {@code (1,1,1,1)}.
     *
     * @return the composed tint, or {@code null} for white identity
     */
    @Nullable
    public Vector4f getComposedTint() {
        return composedTint;
    }

    /** Sets the composed tint; {@code null} = white identity sentinel. */
    public void setComposedTint(@Nullable Vector4f composedTint) {
        this.composedTint = composedTint;
    }

    /** Returns the additive attach_layer list in source order (never {@code null}). */
    public List<LayerData> getLayers() {
        return layers;
    }

    /** Replaces the additive attach_layer list (copied defensively). */
    public void setLayers(List<LayerData> layers) {
        this.layers = layers == null ? List.of() : List.copyOf(layers);
    }

    /**
     * Client-side per-layer render data, mirroring the wire
     * {@code FullBulletEntry.LayerEntryFull} shape. Fields are mutable via
     * the record (design decision: {@code onVisualTick} hooks may reassign
     * the list via {@link BulletRenderObject#setLayers} to animate layers
     * in-flight; individual entries stay immutable).
     *
     * @param renderMode     {@code "billboard"} or {@code "3d"} tag
     * @param texture        billboard texture path, {@code null} for 3d layers
     * @param model          3d model path, {@code null} for billboard layers
     * @param followRotation whether the layer rotates with the bullet's flight
     *                       direction
     * @param followScale    whether the layer inherits the base renderScale
     * @param offsetX/Y/Z    positional offset relative to the base center
     * @param scale          per-layer scale multiplier
     * @param tint           per-layer tint, {@code null} = white identity
     */
    public record LayerData(
            String renderMode,
            @Nullable ResourceLocation texture,
            @Nullable ResourceLocation model,
            boolean followRotation,
            boolean followScale,
            float offsetX,
            float offsetY,
            float offsetZ,
            float scale,
            @Nullable Vector4f tint) {
    }
}
