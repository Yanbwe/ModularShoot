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
 * <p><b>Size contract:</b> the design doc requires overlay textures to match
 * the base texture dimensions. When they differ, the intersection area is
 * blended and a warning is logged; out-of-bounds overlay pixels are ignored
 * and uncovered base pixels are left untouched.</p>
 *
 * @see PluginOverlayCompositor
 */
public final class CompositeTextureBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CompositeTextureBuilder() {
    }

    /**
     * Composites a list of overlay textures onto a base texture, returning a
     * single blended {@link NativeImage}.
     *
     * <p>Overlays are applied in list order: the first overlay is blended on
     * top of the base, the second on top of that result, and so on. This
     * means the <em>last</em> overlay in the list ends up on top. Callers
     * should pass the list produced by
     * {@link PluginOverlayCompositor#collectOverlayTextures}, which is sorted
     * low-layer-to-high so higher layers naturally render above lower
     * ones.</p>
     *
     * <p>The returned image is newly allocated and <strong>not</strong>
     * closed by this method. The caller owns it and must close it after
     * uploading (or when discarding it) to avoid native memory leaks.</p>
     *
     * @param baseTexture     the gun's base (or shoot) texture path
     * @param overlayTextures the sorted overlay texture paths, bottom-to-top
     * @return the composited image, or {@code null} when the base texture
     *         cannot be loaded (an error is logged); missing overlay
     *         textures are skipped with a warning
     */
    @Nullable
    public static NativeImage composite(
            ResourceLocation baseTexture,
            List<ResourceLocation> overlayTextures) {

        NativeImage base = loadTexture(baseTexture);
        if (base == null) {
            LOGGER.error("Failed to load base texture {}; cannot composite overlays", baseTexture);
            return null;
        }
        for (ResourceLocation overlayLocation : overlayTextures) {
            NativeImage overlay = loadTexture(overlayLocation);
            if (overlay == null) {
                continue;
            }
            try {
                blendOnto(base, overlay);
            } finally {
                overlay.close();
            }
        }
        return base;
    }

    /**
     * Alpha-blends every pixel of {@code overlay} onto the corresponding
     * pixel of {@code base}, in place.
     *
     * <p>Only the intersection of the two image dimensions is processed.
     * When the dimensions differ a warning is logged once per overlay so
     * resource authors can fix the mismatch.</p>
     *
     * @param base    the accumulating image; modified in place
     * @param overlay the source image; read-only, closed by the caller
     */
    private static void blendOnto(NativeImage base, NativeImage overlay) {
        int width = Math.min(base.getWidth(), overlay.getWidth());
        int height = Math.min(base.getHeight(), overlay.getHeight());
        if (overlay.getWidth() != base.getWidth() || overlay.getHeight() != base.getHeight()) {
            LOGGER.warn(
                    "Overlay texture size {}x{} does not match base {}x{}; blending intersection only",
                    overlay.getWidth(), overlay.getHeight(), base.getWidth(), base.getHeight());
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int basePixel = base.getPixelRGBA(x, y);
                int overlayPixel = overlay.getPixelRGBA(x, y);
                base.setPixelRGBA(x, y, blendPixel(basePixel, overlayPixel));
            }
        }
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
