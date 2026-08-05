package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Optional;
import net.minecraft.util.FastColor;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.plugin.OutlineSpec;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayBlend;
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
    private static final int WHITE = FastColor.ABGR32.color(255, 255, 255, 255);
    private static final int BLACK = FastColor.ABGR32.color(255, 0, 0, 0);

    private static final Vector4f WHITE_TINT = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
    private static final int OUTLINE_RED = FastColor.ABGR32.color(255, 0, 0, 255);

    /** Builds an {@link OutlineSpec} with the given RGB colour and width. */
    private static OutlineSpec spec(float r, float g, float b, float alpha, int width) {
        return new OutlineSpec(new Vector3f(r, g, b), alpha, width);
    }

    /** Builds an 8x8 canvas with an opaque 4x4 white square at (2,2)-(5,5);
     *  every other pixel is explicitly transparent (NativeImage's native
     *  backing memory is not zero-initialised). */
    private static NativeImage squareCanvas() {
        NativeImage img = solid(8, 8, TRANSPARENT);
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        return img;
    }

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
        return new OverlayLayer(null, alignment, fit, WHITE_TINT, OverlayBlend.NORMAL, Optional.empty());
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

    // --- blend modes (blendPixel) ---

    @Test
    void normalKeepsLegacyOverCompositing() {
        // Opaque green over opaque red: the fast path replaces the base.
        assertEquals(OVERLAY_GREEN,
                CompositeTextureBuilder.blendPixel(BASE_RED, OVERLAY_GREEN, OverlayBlend.NORMAL),
                "opaque overlay replaces base");
        // Semi-transparent green (alpha 128) over opaque red: the general
        // over formula gives green = round(255 × 128/255) = 128 and
        // red = round(255 × 127/255) = 127, exactly the legacy behaviour.
        int out = CompositeTextureBuilder.blendPixel(BASE_RED, FastColor.ABGR32.color(128, 0, 255, 0),
                OverlayBlend.NORMAL);
        assertEquals(255, FastColor.ABGR32.alpha(out), "alpha stays opaque");
        assertEquals(128, FastColor.ABGR32.green(out), "green weighted by src alpha");
        assertEquals(127, FastColor.ABGR32.red(out), "red weighted by remaining alpha");
        assertEquals(0, FastColor.ABGR32.blue(out), "blue stays zero");
    }

    @Test
    void multiplyDarkens() {
        // White base × opaque red: C = (1×1, 0, 0) → pure red.
        assertEquals(FastColor.ABGR32.color(255, 0, 0, 255),
                CompositeTextureBuilder.blendPixel(WHITE, FastColor.ABGR32.color(255, 0, 0, 255),
                        OverlayBlend.MULTIPLY),
                "white × opaque red");
        // Semi-transparent red (alpha 128) over white: C = (1, 0, 0), so
        // out = C × 128/255 + D × 127/255 → red 255, green = blue = 127.
        int out = CompositeTextureBuilder.blendPixel(WHITE, FastColor.ABGR32.color(128, 0, 0, 255),
                OverlayBlend.MULTIPLY);
        assertEquals(255, FastColor.ABGR32.alpha(out), "alpha stays opaque");
        assertEquals(255, FastColor.ABGR32.red(out), "red keeps full intensity");
        assertEquals(127, FastColor.ABGR32.green(out), "green weighted by remaining alpha");
        assertEquals(127, FastColor.ABGR32.blue(out), "blue weighted by remaining alpha");
    }

    @Test
    void screenBrightens() {
        // Black base × gray (128,128,128): 1-(1-128/255)(1-0) = 128/255 per channel.
        assertEquals(FastColor.ABGR32.color(255, 128, 128, 128),
                CompositeTextureBuilder.blendPixel(BLACK, FastColor.ABGR32.color(255, 128, 128, 128),
                        OverlayBlend.SCREEN),
                "black × gray screen");
    }

    @Test
    void addClampsToOne() {
        int red200 = FastColor.ABGR32.color(255, 0, 0, 200); // opaque, r = 200
        // White base + red (200,0,0): r = 255 + 200 would overflow, clamped to 255.
        assertEquals(WHITE, CompositeTextureBuilder.blendPixel(WHITE, red200, OverlayBlend.ADD),
                "white base clamped at 255");
        // Mid-tone base makes the clamp observable: r = 100 + 200 = 300 → 255.
        int mid = CompositeTextureBuilder.blendPixel(FastColor.ABGR32.color(255, 100, 100, 100), red200,
                OverlayBlend.ADD);
        assertEquals(255, FastColor.ABGR32.red(mid), "red clamped to 255");
        assertEquals(100, FastColor.ABGR32.green(mid), "green unchanged");
        assertEquals(100, FastColor.ABGR32.blue(mid), "blue unchanged");
        // Black base + (200,100,50): nothing to clamp, channels pass through.
        assertEquals(FastColor.ABGR32.color(255, 50, 100, 200),
                CompositeTextureBuilder.blendPixel(BLACK, FastColor.ABGR32.color(255, 50, 100, 200),
                        OverlayBlend.ADD),
                "black base passes channels through");
    }

    @Test
    void alphaStaysOverInAllModes() {
        // Multiply with a semi-transparent source over an opaque base:
        // out.alpha = round((128/255 + 1×(1-128/255))×255) = 255.
        int overOpaque = CompositeTextureBuilder.blendPixel(BASE_RED, FastColor.ABGR32.color(128, 0, 255, 0),
                OverlayBlend.MULTIPLY);
        assertEquals(255, FastColor.ABGR32.alpha(overOpaque), "opaque base keeps alpha opaque");
        // General formula with a semi-transparent base (128, 10, 20, 30) and a
        // semi-transparent source (64, 200, 0, 0):
        // out.alpha = round((64/255 + 128/255×(1-64/255))×255) = round(159.88) = 160.
        int semi = CompositeTextureBuilder.blendPixel(FastColor.ABGR32.color(128, 30, 20, 10),
                FastColor.ABGR32.color(64, 0, 0, 200), OverlayBlend.MULTIPLY);
        assertEquals(160, FastColor.ABGR32.alpha(semi), "general alpha-over formula");
    }

    // --- tint (applyTint) ---

    private static int tinted(int pixel, Vector4f tint) {
        try (NativeImage img = new NativeImage(1, 1, false)) {
            img.setPixelRGBA(0, 0, pixel);
            CompositeTextureBuilder.applyTint(img, tint);
            return img.getPixelRGBA(0, 0);
        }
    }

    @Test
    void tintMultipliesChannels() {
        int out = tinted(WHITE, new Vector4f(0.5F, 1.0F, 1.0F, 1.0F));
        assertEquals(128, FastColor.ABGR32.red(out), "red halved (round(127.5))");
        assertEquals(255, FastColor.ABGR32.green(out), "green unchanged");
        assertEquals(255, FastColor.ABGR32.blue(out), "blue unchanged");
        assertEquals(255, FastColor.ABGR32.alpha(out), "alpha unchanged");
    }

    @Test
    void tintMultipliesAlpha() {
        int out = tinted(WHITE, new Vector4f(1.0F, 1.0F, 1.0F, 0.5F));
        assertEquals(128, FastColor.ABGR32.alpha(out), "alpha halved (round(127.5))");
        assertEquals(255, FastColor.ABGR32.red(out), "channels unchanged");
    }

    @Test
    void whiteTintIsIdentity() {
        int pixel = FastColor.ABGR32.color(200, 30, 120, 250); // arbitrary non-white pixel
        assertEquals(pixel, tinted(pixel, WHITE_TINT), "white tint leaves every byte unchanged");
    }

    // --- outline (outlineLayer) ---

    @Test
    void outlinesOnePixelRing() {
        // 8x8 canvas with an opaque 4x4 square at (2,2)-(5,5): a 1px ring of
        // outline red hugs the square's edges; the square keeps its colour and
        // pixels two or more away stay fully transparent.
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 1));
            assertPixel(img, 2, 1, OUTLINE_RED, "ring above the square");
            assertPixel(img, 1, 2, OUTLINE_RED, "ring left of the square");
            assertPixel(img, 6, 2, OUTLINE_RED, "ring right of the square");
            assertPixel(img, 2, 6, OUTLINE_RED, "ring below the square");
            assertPixel(img, 2, 2, WHITE, "square interior keeps its colour");
            assertPixel(img, 5, 5, WHITE, "square interior keeps its colour");
            assertPixel(img, 0, 0, TRANSPARENT, "corner stays transparent");
            assertPixel(img, 7, 7, TRANSPARENT, "corner stays transparent");
        }
    }

    @Test
    void outlineWidthTwo() {
        // Same canvas, 2px ring: every pixel outside the square is within the
        // ring, including the canvas corners (Chebyshev distance 2).
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 2));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean inside = x >= 2 && x < 6 && y >= 2 && y < 6;
                    assertPixel(img, x, y, inside ? WHITE : OUTLINE_RED,
                            inside ? "square interior" : "ring pixel");
                }
            }
        }
    }

    @Test
    void ignoresNonPositiveWidth() {
        // width=0: nothing is painted, every pixel stays byte-identical.
        try (NativeImage img = squareCanvas();
             NativeImage reference = squareCanvas()) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 0));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertPixel(img, x, y, reference.getPixelRGBA(x, y), "byte-identical pixel");
                }
            }
        }
    }

    @Test
    void transparentImageGetsNoOutline() {
        // A fully transparent image has no alpha silhouette to stroke.
        try (NativeImage img = solid(4, 4, TRANSPARENT)) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 1));
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertPixel(img, x, y, TRANSPARENT, "transparent pixel stays transparent");
                }
            }
        }
    }

    @Test
    void outlineIsPaintedAfterTint() {
        // 6x6 canvas with an opaque 4x4 white square at (1,1)-(4,4). Tinting
        // first turns the square magenta; the blue outline painted afterwards
        // must not be affected by the tint (order contract: tint → outline).
        NativeImage img = solid(6, 6, TRANSPARENT);
        for (int y = 1; y < 5; y++) {
            for (int x = 1; x < 5; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        try (img) {
            CompositeTextureBuilder.applyTint(img, new Vector4f(1.0F, 0.0F, 1.0F, 1.0F));
            CompositeTextureBuilder.outlineLayer(img, spec(0.0F, 0.0F, 1.0F, 1.0F, 1));
            assertPixel(img, 2, 2, FastColor.ABGR32.color(255, 255, 0, 255), "tinted square pixel");
            assertPixel(img, 0, 1, FastColor.ABGR32.color(255, 255, 0, 0), "blue outline left");
            assertPixel(img, 5, 5, FastColor.ABGR32.color(255, 255, 0, 0), "blue outline corner");
        }
    }
}
