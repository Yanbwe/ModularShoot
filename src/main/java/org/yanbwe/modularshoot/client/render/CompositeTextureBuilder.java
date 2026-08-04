package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayFit;

/**
 * 2D layer compositor that stacks plugin overlay textures onto a gun's base
 * texture using {@link NativeImage} alpha blending.
 *
 * <p>The design doc (§插件纹理叠加) specifies that overlay compositing is
 * pure 2D layer blending: each plugin texture is alpha-composited on top of
 * the gun's current render texture (base {@code texture} or
 * {@code shoot_texture}), with transparent regions of an overlay letting the
 * layer below show through. The result is a single {@link NativeImage} that
 * the caller can upload to a GPU texture and feed into the vanilla item
 * render pipeline.</p>
 *
 * <p><b>Placement and sizing:</b> each {@link OverlayLayer} carries its own
 * {@link OverlayAlignment} (nine-grid placement, only effective when the fit
 * is {@link OverlayFit#NONE}) and {@link OverlayFit} (keep pixel size /
 * stretch to cover the canvas / scale to fit inside it). Fitted layers are
 * resampled with bilinear interpolation; unfitted layers are blended
 * pixel-for-pixel and clipped at the canvas edges (with a warning when
 * pixels are actually cut off — an overlay smaller than the base is a
 * legitimate centred/corner badge and logs nothing).</p>
 *
 * <p><b>Pixel format:</b> {@link NativeImage#getPixelRGBA} returns pixels in
 * ABGR layout (alpha in the high byte, then blue, green, red). All colour
 * extraction and reconstruction uses {@link FastColor.ABGR32} helpers to
 * stay consistent with the vanilla format.</p>
 *
 * <p><b>Threading:</b> texture loading and per-pixel blending do not assert
 * the render thread, so {@link #composite} may run off-thread. The returned
 * {@code NativeImage} must be {@linkplain NativeImage#close() closed} by the
 * caller (it is {@link AutoCloseable}); its {@code upload()} call, however,
 * must happen on the render thread.</p>
 *
 * @see PluginOverlayCompositor
 */
public final class CompositeTextureBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompositeTextureBuilder() {
    }

    /**
     * A single overlay layer ready for compositing: the texture path plus
     * the placement ({@code alignment}) and sizing ({@code fit}) parameters
     * from the plugin's {@code texture_overlay} definition.
     *
     * @param texture   the overlay texture path
     * @param alignment nine-grid placement; only effective when
     *                  {@code fit} is {@link OverlayFit#NONE}
     * @param fit       how the overlay is fitted into the base canvas
     */
    public record OverlayLayer(ResourceLocation texture, OverlayAlignment alignment, OverlayFit fit) {
    }

    /**
     * Composites a list of overlay layers onto a base texture, returning a
     * single blended {@link NativeImage}.
     *
     * <p>Overlays are applied in list order: the first overlay is blended on
     * top of the base, the second on top of that result, and so on. This
     * means the <em>last</em> overlay in the list ends up on top. Callers
     * should pass the list produced by
     * {@link PluginOverlayCompositor#collectOverlayLayers}, which is sorted
     * low-layer-to-high so higher layers naturally render above lower
     * ones.</p>
     *
     * <p>The returned image is newly allocated and <strong>not</strong>
     * closed by this method. The caller owns it and must close it after
     * uploading (or when discarding it) to avoid native memory leaks.</p>
     *
     * @param baseTexture the gun's base (or shoot) texture path
     * @param overlays    the sorted overlay layers, bottom-to-top
     * @return the composited image, or {@code null} when the base texture
     *         cannot be loaded (an error is logged); missing overlay
     *         textures are skipped with a warning
     */
    @Nullable
    public static NativeImage composite(
            ResourceLocation baseTexture,
            List<OverlayLayer> overlays) {

        NativeImage base = loadTexture(baseTexture);
        if (base == null) {
            LOGGER.error("Failed to load base texture {}; cannot composite overlays", baseTexture);
            return null;
        }
        for (OverlayLayer layer : overlays) {
            NativeImage overlay = loadTexture(layer.texture());
            if (overlay == null) {
                continue;
            }
            try {
                blendOnto(base, overlay, layer);
            } finally {
                overlay.close();
            }
        }
        return base;
    }

    /**
     * Alpha-blends an overlay onto the base image, in place, at the position
     * and size determined by the layer's alignment and fit.
     *
     * <p><b>Target region:</b> {@link OverlayFit#FILL} covers the whole
     * canvas, {@link OverlayFit#CONTAIN} scales uniformly to the largest
     * fully-visible size and centres it, and {@link OverlayFit#NONE} keeps
     * the overlay's pixel size at its {@link OverlayAlignment} grid
     * position. Only the intersection of the target region with the base
     * canvas is processed. When {@code fit} is {@code NONE} and the target
     * region extends past a canvas edge (pixels would be clipped), a warning
     * is logged once; a smaller overlay placed inside the canvas is the
     * normal badge case and logs nothing.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure and does
     * not touch any Minecraft state.</p>
     *
     * @param base    the accumulating image; modified in place
     * @param overlay the source image; read-only, closed by the caller
     * @param layer   the placement and sizing parameters for this overlay
     */
    static void blendOnto(NativeImage base, NativeImage overlay, OverlayLayer layer) {
        int baseW = base.getWidth();
        int baseH = base.getHeight();
        int overlayW = overlay.getWidth();
        int overlayH = overlay.getHeight();

        // Target region in base coordinates.
        int dstX;
        int dstY;
        int dstW;
        int dstH;
        if (layer.fit() == OverlayFit.FILL) {
            dstX = 0;
            dstY = 0;
            dstW = baseW;
            dstH = baseH;
        } else if (layer.fit() == OverlayFit.CONTAIN) {
            double scale = Math.min((double) baseW / overlayW, (double) baseH / overlayH);
            dstW = (int) Math.round(overlayW * scale);
            dstH = (int) Math.round(overlayH * scale);
            dstX = (baseW - dstW) / 2;
            dstY = (baseH - dstH) / 2;
        } else {
            dstW = overlayW;
            dstH = overlayH;
            dstX = alignOffset(layer.alignment(), baseW, overlayW, true);
            dstY = alignOffset(layer.alignment(), baseH, overlayH, false);
        }

        // Intersection of the target region with the base canvas.
        int startX = Math.max(0, dstX);
        int startY = Math.max(0, dstY);
        int endX = Math.min(baseW, dstX + dstW);
        int endY = Math.min(baseH, dstY + dstH);
        if (endX <= startX || endY <= startY) {
            LOGGER.warn("Overlay {} is entirely outside the base texture; skipped", layer.texture());
            return;
        }
        if (layer.fit() == OverlayFit.NONE
                && (dstX < 0 || dstY < 0 || dstX + dstW > baseW || dstY + dstH > baseH)) {
            LOGGER.warn(
                    "Overlay {} ({}x{}) extends beyond the base texture ({}x{}); clipped at canvas edges",
                    layer.texture(), overlayW, overlayH, baseW, baseH);
        }

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int basePixel = base.getPixelRGBA(x, y);
                int overlayPixel;
                if (layer.fit() == OverlayFit.NONE) {
                    overlayPixel = overlay.getPixelRGBA(x - dstX, y - dstY);
                } else {
                    // Pixel-centre mapping back into overlay coordinates.
                    float srcX = ((x - dstX) + 0.5F) * (overlayW / (float) dstW) - 0.5F;
                    float srcY = ((y - dstY) + 0.5F) * (overlayH / (float) dstH) - 0.5F;
                    overlayPixel = sampleBilinear(overlay, srcX, srcY);
                }
                base.setPixelRGBA(x, y, blendPixel(basePixel, overlayPixel));
            }
        }
    }

    /**
     * Computes the top-left offset of the overlay along one axis for the
     * given alignment.
     *
     * @param alignment  the nine-grid alignment
     * @param canvas     the base canvas size along this axis
     * @param item       the overlay size along this axis
     * @param horizontal {@code true} for the x axis, {@code false} for y
     * @return the offset of the overlay's near edge within the canvas
     */
    private static int alignOffset(OverlayAlignment alignment, int canvas, int item, boolean horizontal) {
        if (horizontal) {
            return switch (alignment) {
                case TOP_LEFT, CENTER_LEFT, BOTTOM_LEFT -> 0;
                case TOP_CENTER, CENTER, BOTTOM_CENTER -> (canvas - item) / 2;
                case TOP_RIGHT, CENTER_RIGHT, BOTTOM_RIGHT -> canvas - item;
            };
        }
        return switch (alignment) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 0;
            case CENTER_LEFT, CENTER, CENTER_RIGHT -> (canvas - item) / 2;
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> canvas - item;
        };
    }

    /**
     * Samples the overlay at a continuous pixel coordinate with bilinear
     * interpolation, clamping to the image bounds.
     *
     * @param img the overlay image
     * @param x   the continuous x coordinate in pixel space
     * @param y   the continuous y coordinate in pixel space
     * @return the interpolated pixel in ABGR layout
     */
    private static int sampleBilinear(NativeImage img, float x, float y) {
        int w = img.getWidth();
        int h = img.getHeight();
        float u = Math.max(0F, Math.min(w - 1F, x));
        float v = Math.max(0F, Math.min(h - 1F, y));
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        int x1 = Math.min(x0 + 1, w - 1);
        int y1 = Math.min(y0 + 1, h - 1);
        float fx = u - x0;
        float fy = v - y0;

        int p00 = img.getPixelRGBA(x0, y0);
        int p10 = img.getPixelRGBA(x1, y0);
        int p01 = img.getPixelRGBA(x0, y1);
        int p11 = img.getPixelRGBA(x1, y1);

        // Horizontal lerp first, then vertical; one pass per ABGR channel.
        int alpha = Math.round(lerp(lerp(FastColor.ABGR32.alpha(p00), FastColor.ABGR32.alpha(p10), fx),
                lerp(FastColor.ABGR32.alpha(p01), FastColor.ABGR32.alpha(p11), fx), fy));
        int red = Math.round(lerp(lerp(FastColor.ABGR32.red(p00), FastColor.ABGR32.red(p10), fx),
                lerp(FastColor.ABGR32.red(p01), FastColor.ABGR32.red(p11), fx), fy));
        int green = Math.round(lerp(lerp(FastColor.ABGR32.green(p00), FastColor.ABGR32.green(p10), fx),
                lerp(FastColor.ABGR32.green(p01), FastColor.ABGR32.green(p11), fx), fy));
        int blue = Math.round(lerp(lerp(FastColor.ABGR32.blue(p00), FastColor.ABGR32.blue(p10), fx),
                lerp(FastColor.ABGR32.blue(p01), FastColor.ABGR32.blue(p11), fx), fy));
        return FastColor.ABGR32.color(alpha, blue, green, red);
    }

    /**
     * Linear interpolation between two values.
     *
     * @param a the value at {@code t = 0}
     * @param b the value at {@code t = 1}
     * @param t the interpolation factor
     * @return {@code a + (b - a) * t}
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Alpha-blends a source (overlay) pixel over a destination (base) pixel
     * using the standard "over" compositing operator for non-premultiplied
     * alpha.
     *
     * <p>Fast paths: a fully transparent overlay ({@code alpha == 0}) returns
     * the base unchanged; a fully opaque overlay ({@code alpha == 255})
     * replaces the base entirely. The general case uses
     * {@code outA = srcA + dstA * (1 - srcA)} and the corresponding weighted
     * colour average, which correctly handles semi-transparent overlays over
     * both opaque and transparent bases.</p>
     *
     * @param base    the destination pixel in ABGR layout
     * @param overlay the source pixel in ABGR layout
     * @return the composited pixel in ABGR layout
     */
    private static int blendPixel(int base, int overlay) {
        int overlayAlpha = FastColor.ABGR32.alpha(overlay);
        if (overlayAlpha == 0) {
            return base;
        }
        if (overlayAlpha == 255) {
            return overlay;
        }
        int baseAlpha = FastColor.ABGR32.alpha(base);
        float srcA = overlayAlpha / 255.0F;
        float dstA = baseAlpha / 255.0F;
        float oneMinusSrcA = 1.0F - srcA;
        float outA = srcA + dstA * oneMinusSrcA;
        float invOutA = 1.0F / outA;

        int red = Math.round((FastColor.ABGR32.red(overlay) * srcA
                + FastColor.ABGR32.red(base) * dstA * oneMinusSrcA) * invOutA);
        int green = Math.round((FastColor.ABGR32.green(overlay) * srcA
                + FastColor.ABGR32.green(base) * dstA * oneMinusSrcA) * invOutA);
        int blue = Math.round((FastColor.ABGR32.blue(overlay) * srcA
                + FastColor.ABGR32.blue(base) * dstA * oneMinusSrcA) * invOutA);
        int alpha = Math.round(outA * 255.0F);
        return FastColor.ABGR32.color(alpha, blue, green, red);
    }

    /**
     * Loads a PNG texture from the client resource manager into a
     * {@link NativeImage}.
     *
     * <p>{@link NativeImage#read(InputStream)} internally closes the stream
     * in its {@code finally} block; the surrounding try-with-resources is
     * kept for safety on the rare path where {@code open()} succeeds but
     * {@code read} is never reached.</p>
     *
     * @param location the texture resource path
     * @return the loaded image, or {@code null} when the resource is absent
     *         or cannot be decoded (an error is logged)
     */
    @Nullable
    private static NativeImage loadTexture(ResourceLocation location) {
        Optional<Resource> resourceOpt = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resourceOpt.isEmpty()) {
            LOGGER.warn("Texture resource not found: {}", location);
            return null;
        }
        try (InputStream stream = resourceOpt.get().open()) {
            return NativeImage.read(stream);
        } catch (IOException e) {
            LOGGER.error("Failed to load texture {}", location, e);
            return null;
        }
    }
}
