package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.FastColor;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayFit;
import org.yanbwe.modularshoot.client.render.CompositeTextureBuilder.OverlayLayer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompositeTextureBuilder#blendOnto}: nine-grid
 * alignment, fill / contain resampling and edge clipping. The pixel math is
 * pure (no Minecraft state), so it is exercised with hand-built
 * {@link NativeImage} canvases.
 */
class CompositeTextureBuilderTest {

    private static final int BASE_RED = FastColor.ABGR32.color(255, 0, 0, 255);
    private static final int OVERLAY_GREEN = FastColor.ABGR32.color(255, 0, 255, 0);
    private static final int TRANSPARENT = FastColor.ABGR32.color(0, 0, 0, 0);

    /** Builds a solid-colour image. */
    private static NativeImage solid(int w, int h, int abgr) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, abgr);
            }
        }
        return img;
    }

    private static OverlayLayer layer(OverlayAlignment alignment, OverlayFit fit) {
        return new OverlayLayer(null, alignment, fit);
    }

    private static void blend(NativeImage base, int overlayW, int overlayH, OverlayAlignment alignment, OverlayFit fit) {
        try (NativeImage overlay = solid(overlayW, overlayH, OVERLAY_GREEN)) {
            CompositeTextureBuilder.blendOnto(base, overlay, layer(alignment, fit));
        }
    }

    private static void assertPixel(NativeImage img, int x, int y, int expectedAbgr, String what) {
        assertEquals(expectedAbgr, img.getPixelRGBA(x, y), what + " at (" + x + "," + y + ")");
    }

    // --- alignment (fit = NONE) ---

    @Test
    void topLeftIsLegacyCornerBehaviour() {
        // 16x16 base + 8x8 overlay at the top-left corner (default).
        try (NativeImage base = solid(16, 16, BASE_RED)) {
            blend(base, 8, 8, OverlayAlignment.TOP_LEFT, OverlayFit.NONE);
            assertPixel(base, 0, 0, OVERLAY_GREEN, "overlay corner");
            assertPixel(base, 7, 7, OVERLAY_GREEN, "overlay edge");
            assertPixel(base, 8, 8, BASE_RED, "outside overlay");
            assertPixel(base, 15, 15, BASE_RED, "far corner");
        }
    }

    @Test
    void centerPlacesOverlayInMiddle() {
        // 32x32 base + 16x16 overlay centred → target (8,8)-(24,24).
        try (NativeImage base = solid(32, 32, BASE_RED)) {
            blend(base, 16, 16, OverlayAlignment.CENTER, OverlayFit.NONE);
            assertPixel(base, 0, 0, BASE_RED, "top-left canvas corner");
            assertPixel(base, 31, 31, BASE_RED, "bottom-right canvas corner");
            assertPixel(base, 8, 8, OVERLAY_GREEN, "overlay top-left");
            assertPixel(base, 16, 16, OVERLAY_GREEN, "overlay centre");
            assertPixel(base, 23, 23, OVERLAY_GREEN, "overlay bottom-right");
            assertPixel(base, 7, 7, BASE_RED, "just outside overlay");
            assertPixel(base, 24, 24, BASE_RED, "just outside overlay");
        }
    }

    @Test
    void bottomRightAlignsToCorner() {
        // 16x16 base + 8x8 overlay at bottom-right → target (8,8)-(16,16).
        try (NativeImage base = solid(16, 16, BASE_RED)) {
            blend(base, 8, 8, OverlayAlignment.BOTTOM_RIGHT, OverlayFit.NONE);
            assertPixel(base, 15, 15, OVERLAY_GREEN, "overlay corner");
            assertPixel(base, 0, 0, BASE_RED, "canvas corner");
            assertPixel(base, 7, 7, BASE_RED, "outside overlay");
        }
    }

    @Test
    void centredOverlayLargerThanBaseIsClipped() {
        // 16x16 base + 32x32 overlay centred → target (-8,-8)-(24,24);
        // the whole base is covered, everything outside is clipped.
        try (NativeImage base = solid(16, 16, BASE_RED)) {
            blend(base, 32, 32, OverlayAlignment.CENTER, OverlayFit.NONE);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertPixel(base, x, y, OVERLAY_GREEN, "covered pixel");
                }
            }
        }
    }

    // --- resampling (fill / contain) ---

    @Test
    void fillCoversWholeCanvas() {
        // 32x32 base + 16x16 overlay stretched over the full canvas.
        try (NativeImage base = solid(32, 32, BASE_RED)) {
            blend(base, 16, 16, OverlayAlignment.TOP_LEFT, OverlayFit.FILL);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    assertPixel(base, x, y, OVERLAY_GREEN, "filled pixel");
                }
            }
        }
    }

    @Test
    void containScalesUniformlyAndCentres() {
        // 32x32 base + 16x8 overlay → scale = min(32/16, 32/8) = 2 →
        // target 32x16 centred at (0,8)-(32,24): top/bottom margins stay base.
        try (NativeImage base = solid(32, 32, BASE_RED)) {
            blend(base, 16, 8, OverlayAlignment.TOP_LEFT, OverlayFit.CONTAIN);
            assertPixel(base, 16, 0, BASE_RED, "top margin");
            assertPixel(base, 16, 7, BASE_RED, "top margin edge");
            assertPixel(base, 16, 8, OVERLAY_GREEN, "overlay top edge");
            assertPixel(base, 0, 16, OVERLAY_GREEN, "overlay left edge");
            assertPixel(base, 31, 16, OVERLAY_GREEN, "overlay right edge");
            assertPixel(base, 16, 23, OVERLAY_GREEN, "overlay bottom edge");
            assertPixel(base, 16, 24, BASE_RED, "bottom margin edge");
            assertPixel(base, 16, 31, BASE_RED, "bottom margin");
        }
    }

    @Test
    void containWithMatchingAspectFillsCompletely() {
        // 32x32 base + 16x16 overlay → scale = 2 → full 32x32 cover.
        try (NativeImage base = solid(32, 32, BASE_RED)) {
            blend(base, 16, 16, OverlayAlignment.TOP_LEFT, OverlayFit.CONTAIN);
            assertPixel(base, 0, 0, OVERLAY_GREEN, "corner");
            assertPixel(base, 31, 31, OVERLAY_GREEN, "corner");
        }
    }

    // --- alpha blending ---

    @Test
    void semiTransparentOverlayBlendsOverOpaqueBase() {
        // Base red (255,0,0), overlay green alpha=128 over it:
        // red = 255 * (1 - 128/255) = 127, green = 255 * 128/255 = 128.
        NativeImage base = new NativeImage(1, 1, false);
        base.setPixelRGBA(0, 0, BASE_RED);
        NativeImage overlay = new NativeImage(1, 1, false);
        overlay.setPixelRGBA(0, 0, FastColor.ABGR32.color(128, 0, 255, 0));
        try (base; overlay) {
            CompositeTextureBuilder.blendOnto(base, overlay, layer(OverlayAlignment.CENTER, OverlayFit.NONE));
            int pixel = base.getPixelRGBA(0, 0);
            assertEquals(255, FastColor.ABGR32.alpha(pixel), "alpha stays opaque");
            assertEquals(127, FastColor.ABGR32.red(pixel), "red weighted by remaining alpha");
            assertEquals(128, FastColor.ABGR32.green(pixel), "green weighted by overlay alpha");
            assertEquals(0, FastColor.ABGR32.blue(pixel), "blue stays zero");
        }
    }

    @Test
    void fullyTransparentOverlayLeavesBaseUntouched() {
        try (NativeImage base = solid(4, 4, BASE_RED);
             NativeImage overlay = solid(2, 2, TRANSPARENT)) {
            CompositeTextureBuilder.blendOnto(base, overlay, layer(OverlayAlignment.CENTER, OverlayFit.NONE));
            assertPixel(base, 0, 0, BASE_RED, "corner");
            assertPixel(base, 1, 1, BASE_RED, "overlay area");
        }
    }
}
