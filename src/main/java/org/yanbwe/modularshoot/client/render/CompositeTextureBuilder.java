package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FastColor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.yanbwe.modularshoot.plugin.OutlineSpec;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayBlend;
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
 * <p><b>Blend modes:</b> each {@link OverlayLayer} may select an
 * {@link OverlayBlend} mode controlling how its colour channels combine with
 * the layers beneath: {@link OverlayBlend#MULTIPLY} multiplies the colours
 * (darkening), {@link OverlayBlend#SCREEN} inverse-multiplies them
 * (brightening, filter mode) and {@link OverlayBlend#ADD} sums them with
 * clamping to 1 (brightening). Alpha always uses ordinary over compositing
 * regardless of the blend mode. A per-layer {@code tint} multiplies the
 * overlay's RGBA pixels channel-by-channel before blending (white is the
 * identity).</p>
 *
 * <p><b>Per-layer processing order:</b> each overlay is tinted first, then
 * its optional outline stroke is painted along the alpha silhouette
 * ({@link #outlineLayer}), and only then is the result blended onto the
 * base. The outline colour is applied after the tint, so it never
 * participates in tinting — callers must rely on this call order rather
 * than pre-tinting the outline colour.</p>
 *
 * <p><b>Threading:</b> texture loading and per-pixel blending do not assert
 * the render thread, so {@link #composite} may run off-thread. The returned
 * {@code NativeImage} must be {@linkplain NativeImage#close() closed} by the
 * caller (it is {@link AutoCloseable}); its {@code upload()} call, however,
 * must happen on the render thread.</p>
 *
 * <p><b>Whole-gun outlines:</b> after all overlays are blended, an optional
 * list of {@link OutlineSpec} gun outlines is painted around the composited
 * silhouette by {@link #applyGunOutlines}. Outlines are drawn widest-first
 * (a stable sort keeps the caller's install order when widths tie, so the
 * later-installed outline paints over the earlier one), which makes narrower
 * rings nest concentrically inside wider ones. Every layer's decision is
 * based on a snapshot of the composited alpha taken before any outline is
 * painted, so an earlier ring never widens the silhouette that later rings
 * hug. Each layer overwrites the pixels it marks.</p>
 *
 * @see PluginOverlayCompositor
 */
public final class CompositeTextureBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompositeTextureBuilder() {
    }

    /**
     * A single overlay layer ready for compositing: the texture path plus the
     * placement ({@code alignment}) and sizing ({@code fit}) parameters from
     * the plugin's {@code texture_overlay} definition, together with its
     * colour tint, blend mode and optional outline stroke.
     *
     * @param texture   the overlay texture path
     * @param alignment nine-grid placement; only effective when
     *                  {@code fit} is {@link OverlayFit#NONE}
     * @param fit       how the overlay is fitted into the base canvas
     * @param tint      per-channel RGBA multiplier applied to the overlay
     *                  pixels before blending; white (1,1,1,1) is the identity
     * @param blend     how the overlay's colour combines with the underlying
     *                  layers ({@link OverlayBlend})
     * @param outline   optional stroke painted along the overlay's alpha edge
     *                  after tinting; empty when no outline is drawn
     */
    public record OverlayLayer(ResourceLocation texture, OverlayAlignment alignment, OverlayFit fit,
                               Vector4f tint, OverlayBlend blend, Optional<OutlineSpec> outline) {
    }

    /**
     * Composites a list of overlay layers onto a base texture, then paints
     * the given whole-gun outlines around the composited silhouette, returning
     * a single blended {@link NativeImage}.
     *
     * <p>Overlays are applied in list order: the first overlay is blended on
     * top of the base, the second on top of that result, and so on. This
     * means the <em>last</em> overlay in the list ends up on top. Callers
     * should pass the list produced by
     * {@link PluginOverlayCompositor#collectOverlayLayers}, which is sorted
     * low-layer-to-high so higher layers naturally render above lower
     * ones.</p>
     *
     * <p>Gun outlines are painted after all overlays by
     * {@link #applyGunOutlines} — see its contract for the draw order (width
     * descending, stable tie-breaking, alpha-snapshot based) and the class
     * javadoc for the rationale.</p>
     *
     * <p>The returned image is newly allocated and <strong>not</strong>
     * closed by this method. The caller owns it and must close it after
     * uploading (or when discarding it) to avoid native memory leaks.</p>
     *
     * @param baseTexture the gun's base (or shoot) texture path
     * @param overlays    the sorted overlay layers, bottom-to-top
     * @param gunOutlines the whole-gun outlines, painted widest-first after
     *                    the overlays; may be empty
     * @return the composited image, or {@code null} when the base texture
     *         cannot be loaded (an error is logged); missing overlay
     *         textures are skipped with a warning
     */
    @Nullable
    public static NativeImage composite(
            ResourceLocation baseTexture,
            List<OverlayLayer> overlays,
            List<OutlineSpec> gunOutlines) {

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
                applyTint(overlay, layer.tint());
                if (layer.outline().isPresent()) {
                    outlineLayer(overlay, layer.outline().get());
                }
                blendOnto(base, overlay, layer);
            } finally {
                overlay.close();
            }
        }
        applyGunOutlines(base, gunOutlines);
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
                base.setPixelRGBA(x, y, blendPixel(basePixel, overlayPixel, layer.blend()));
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
     * Blends a source (overlay) pixel over a destination (base) pixel using
     * the standard "over" compositing operator for non-premultiplied alpha.
     *
     * <p>The {@code blend} mode only affects the colour channels:
     * {@link OverlayBlend#MULTIPLY} multiplies source and destination colours,
     * {@link OverlayBlend#SCREEN} inverse-multiplies them, {@link OverlayBlend#ADD}
     * sums them clamped to 1, and {@link OverlayBlend#NORMAL} (the default)
     * uses the source colour unchanged. Alpha always composites with the over
     * formula {@code outA = srcA + dstA * (1 - srcA)}.</p>
     *
     * <p>Fast paths: a fully transparent overlay ({@code alpha == 0}) returns
     * the base unchanged; a fully opaque overlay in normal mode returns the
     * overlay unchanged. The general case computes the blended colour
     * {@code C} (per blend mode) and then mixes it with the base colour
     * weighted by alpha, which correctly handles semi-transparent overlays
     * over both opaque and transparent bases.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure.</p>
     *
     * @param base    the destination pixel in ABGR layout
     * @param overlay the source pixel in ABGR layout
     * @param blend   the colour mixing mode
     * @return the composited pixel in ABGR layout
     */
    static int blendPixel(int base, int overlay, OverlayBlend blend) {
        int overlayAlpha = FastColor.ABGR32.alpha(overlay);
        if (overlayAlpha == 0) {
            return base;
        }
        if (overlayAlpha == 255 && blend == OverlayBlend.NORMAL) {
            return overlay;
        }
        float srcA = overlayAlpha / 255.0F;
        float dstA = FastColor.ABGR32.alpha(base) / 255.0F;
        float sr = FastColor.ABGR32.red(overlay) / 255.0F;
        float sg = FastColor.ABGR32.green(overlay) / 255.0F;
        float sb = FastColor.ABGR32.blue(overlay) / 255.0F;
        float dr = FastColor.ABGR32.red(base) / 255.0F;
        float dg = FastColor.ABGR32.green(base) / 255.0F;
        float db = FastColor.ABGR32.blue(base) / 255.0F;
        float cr;
        float cg;
        float cb;
        switch (blend) {
            case MULTIPLY -> { cr = sr * dr; cg = sg * dg; cb = sb * db; }
            case SCREEN -> {
                cr = 1.0F - (1.0F - sr) * (1.0F - dr);
                cg = 1.0F - (1.0F - sg) * (1.0F - dg);
                cb = 1.0F - (1.0F - sb) * (1.0F - db);
            }
            case ADD -> {
                cr = Math.min(1.0F, sr + dr);
                cg = Math.min(1.0F, sg + dg);
                cb = Math.min(1.0F, sb + db);
            }
            default -> { cr = sr; cg = sg; cb = sb; } // NORMAL
        }
        float oneMinusSrcA = 1.0F - srcA;
        float outA = srcA + dstA * oneMinusSrcA;
        int or = Math.round((cr * srcA + dr * oneMinusSrcA) * 255.0F);
        int og = Math.round((cg * srcA + dg * oneMinusSrcA) * 255.0F);
        int ob = Math.round((cb * srcA + db * oneMinusSrcA) * 255.0F);
        return FastColor.ABGR32.color(Math.round(outA * 255.0F), ob, og, or);
    }

    /**
     * Multiplies every pixel of an image channel-by-channel with a tint
     * vector, in place.
     *
     * <p>A fully white tint {@code (1,1,1,1)} is the identity and short-
     * circuits without touching the image. Each of the four ABGR channels is
     * scaled by the matching component of {@code tint} and rounded to the
     * nearest byte.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure.</p>
     *
     * @param img  the image to tint, modified in place
     * @param tint per-channel multiplier in 0..1 (RGBA order)
     */
    static void applyTint(NativeImage img, Vector4f tint) {
        if (tint.x() == 1.0F && tint.y() == 1.0F && tint.z() == 1.0F && tint.w() == 1.0F) {
            return; // white identity
        }
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getPixelRGBA(x, y);
                int r = Math.round(FastColor.ABGR32.red(p) * tint.x());
                int g = Math.round(FastColor.ABGR32.green(p) * tint.y());
                int b = Math.round(FastColor.ABGR32.blue(p) * tint.z());
                int a = Math.round(FastColor.ABGR32.alpha(p) * tint.w());
                img.setPixelRGBA(x, y, FastColor.ABGR32.color(a, b, g, r));
            }
        }
    }

    /**
     * Checks whether any pixel within a square neighbourhood of radius
     * {@code width} around {@code (x, y)} has non-zero alpha (including the
     * pixel itself). Neighbourhood cells outside the image bounds are skipped.
     *
     * <p>Used by {@link #outlineLayer} to find transparent pixels hugging an
     * opaque silhouette.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure.</p>
     *
     * @param img   the image to sample
     * @param x     the centre x coordinate
     * @param y     the centre y coordinate
     * @param width the neighbourhood radius in pixels
     * @return {@code true} when an alpha &gt; 0 pixel exists within the
     *         neighbourhood
     */
    static boolean hasAlphaNeighbor(NativeImage img, int x, int y, int width) {
        for (int dy = -width; dy <= width; dy++) {
            for (int dx = -width; dx <= width; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= img.getWidth() || ny >= img.getHeight()) {
                    continue;
                }
                if (FastColor.ABGR32.alpha(img.getPixelRGBA(nx, ny)) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Paints an outline stroke along the image's alpha silhouette, in place:
     * every fully transparent pixel within {@code width} pixels (Chebyshev
     * distance) of a non-transparent pixel is painted with the outline colour.
     *
     * <p>A non-positive {@code width} logs a warning and paints nothing. The
     * stroke colour is fixed and never participates in tinting — the call
     * order in {@link #composite} (tint first, then outline) guarantees this.
     * Two passes are used: pass one marks the target pixels based on the
     * original alpha values, pass two writes the colour, so freshly painted
     * outline pixels never trigger further marking.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure.</p>
     *
     * @param img  the image to stroke, modified in place
     * @param spec the outline colour (RGB in 0..1), opacity and width
     */
    static void outlineLayer(NativeImage img, OutlineSpec spec) {
        int width = spec.width();
        if (width <= 0) {
            LOGGER.warn("Outline width {} is not positive; outline skipped for layer", width);
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] marks = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (FastColor.ABGR32.alpha(img.getPixelRGBA(x, y)) == 0
                        && hasAlphaNeighbor(img, x, y, width)) {
                    marks[y * w + x] = true;
                }
            }
        }
        int color = FastColor.ABGR32.color(
                Math.round(spec.alpha() * 255.0F),
                Math.round(spec.color().z() * 255.0F),
                Math.round(spec.color().y() * 255.0F),
                Math.round(spec.color().x() * 255.0F));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (marks[y * w + x]) {
                    img.setPixelRGBA(x, y, color);
                }
            }
        }
    }

    /**
     * Paints a list of whole-gun outline strokes around the composited alpha
     * silhouette, in place, with concentric nesting.
     *
     * <p><b>Draw order:</b> outlines are sorted by width descending, so wider
     * rings are painted first and narrower rings nest inside them. The sort is
     * stable: when widths tie, the caller's install order is preserved and the
     * later-installed outline paints over the earlier one (last one wins).</p>
     *
     * <p><b>Alpha snapshot:</b> outline detection is always based on the
     * original composited silhouette (an {@code int[]} alpha snapshot taken
     * before any outline is painted), so each ring hugs the same shape and
     * never widens the silhouette for the next ring. Within a layer, pixels
     * may be read-and-written in the same pass because the decision reads
     * only the snapshot. Each layer overwrites the pixels it marks; later
     * layers (narrower rings) naturally cover earlier ones.</p>
     *
     * <p>A non-positive {@code width} logs a warning and paints nothing.
     * An empty list is a no-op.</p>
     *
     * <p>Package-private for unit testing; the pixel math is pure.</p>
     *
     * @param img      the composited image, modified in place
     * @param outlines the whole-gun outline specs, any order; painted
     *                 widest-first
     */
    static void applyGunOutlines(NativeImage img, List<OutlineSpec> outlines) {
        if (outlines.isEmpty()) {
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        // Alpha snapshot: outline detection is always based on the original
        // composited silhouette so that narrower rings nest inside wider ones
        // instead of each ring widening the silhouette for the next.
        int[] alphaSnapshot = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alphaSnapshot[y * w + x] = FastColor.ABGR32.alpha(img.getPixelRGBA(x, y));
            }
        }
        // Stable sort by width descending: List.sort is stable, so ties keep
        // the caller's install order and later installs paint over earlier
        // ones.
        List<OutlineSpec> sorted = new ArrayList<>(outlines);
        sorted.sort(Comparator.comparingInt(OutlineSpec::width).reversed());
        for (OutlineSpec spec : sorted) {
            int width = spec.width();
            if (width <= 0) {
                LOGGER.warn("Gun outline width {} is not positive; outline skipped", width);
                continue;
            }
            int color = FastColor.ABGR32.color(
                    Math.round(spec.alpha() * 255.0F),
                    Math.round(spec.color().z() * 255.0F),
                    Math.round(spec.color().y() * 255.0F),
                    Math.round(spec.color().x() * 255.0F));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (alphaSnapshot[y * w + x] == 0
                            && hasAlphaNeighborInSnapshot(alphaSnapshot, w, h, x, y, width)) {
                        img.setPixelRGBA(x, y, color);
                    }
                }
            }
        }
    }

    /**
     * Checks whether any pixel within a square neighbourhood of radius
     * {@code width} around {@code (x, y)} has non-zero alpha in the given
     * alpha snapshot (including the pixel itself). Neighbourhood cells
     * outside the image bounds are skipped.
     *
     * <p>Counterpart of {@link #hasAlphaNeighbor} that reads a plain alpha
     * snapshot array instead of a live {@link NativeImage}, so outline
     * detection is immune to pixels painted by earlier outline passes.</p>
     *
     * @param alpha the per-pixel alpha snapshot, row-major, 0..255
     * @param w     the snapshot width in pixels
     * @param h     the snapshot height in pixels
     * @param x     the centre x coordinate
     * @param y     the centre y coordinate
     * @param width the neighbourhood radius in pixels
     * @return {@code true} when an alpha &gt; 0 pixel exists within the
     *         neighbourhood
     */
    private static boolean hasAlphaNeighborInSnapshot(int[] alpha, int w, int h, int x, int y, int width) {
        for (int dy = -width; dy <= width; dy++) {
            for (int dx = -width; dx <= width; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                if (alpha[ny * w + nx] > 0) {
                    return true;
                }
            }
        }
        return false;
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
