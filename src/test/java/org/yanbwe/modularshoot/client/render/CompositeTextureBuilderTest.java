package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
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
    private static final int OUTLINE_GOLD = FastColor.ABGR32.color(255, 0, 255, 255);
    private static final int OUTLINE_BLUE = FastColor.ABGR32.color(255, 255, 0, 0);

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

    /** Chebyshev distance of (x, y) to the 4x4 square at (2,2)-(5,5);
     *  0 for pixels inside the square. */
    private static int squareDistance(int x, int y) {
        int dx = Math.max(2 - x, Math.max(0, x - 5));
        int dy = Math.max(2 - y, Math.max(0, y - 5));
        return Math.max(dx, dy);
    }

    /** Chebyshev distance of (x, y) to the 2x2 square at (3,3)-(4,4);
     *  0 for pixels inside the square. */
    private static int square2Distance(int x, int y) {
        int dx = Math.max(3 - x, Math.max(0, x - 4));
        int dy = Math.max(3 - y, Math.max(0, y - 4));
        return Math.max(dx, dy);
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

    /** Asserts a channel value within a rounding tolerance of ±1. */
    private static void assertChannel(int actual, int expected, String what) {
        assertTrue(Math.abs(actual - expected) <= 1,
                what + ": expected " + expected + " ±1, got " + actual);
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

    // --- whole-gun outlines (applyGunOutlines) ---

    @Test
    void emptyListNoOp() {
        // No outlines: every pixel stays byte-identical.
        try (NativeImage img = squareCanvas();
             NativeImage reference = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(img, List.of());
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertPixel(img, x, y, reference.getPixelRGBA(x, y), "byte-identical pixel");
                }
            }
        }
    }

    // --- outline mask (buildOutlineMask) ---

    /** Builds a w×h canvas with an opaque white square from (x1,y1) to (x2,y2)
     *  inclusive; every other pixel is explicitly transparent. */
    private static NativeImage squareCanvas(int w, int h, int x1, int y1, int x2, int y2) {
        NativeImage img = solid(w, h, TRANSPARENT);
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        return img;
    }

    /** Chebyshev distance of (x, y) to the square at (x1,y1)-(x2,y2). */
    private static int squareDistance(int x, int y, int x1, int y1, int x2, int y2) {
        int dx = Math.max(x1 - x, Math.max(0, x - x2));
        int dy = Math.max(y1 - y, Math.max(0, y - y2));
        return Math.max(dx, dy);
    }

    @Test
    void emptyOutlinesReturnNull() {
        assertNull(CompositeTextureBuilder.buildOutlineMask(squareCanvas(), List.of()),
                "an empty outline list yields no mask");
    }

    @Test
    void maskMatchesBakedStrokeFootprint() {
        // The mask must mark exactly the pixels the baked strokes occupy:
        // white opaque at every stroke pixel, transparent at the original
        // silhouette and beyond the stroke. The mask input image is never
        // modified by the call.
        List<OutlineSpec> specs = List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 2));
        try (NativeImage baked = squareCanvas();
             NativeImage maskSource = squareCanvas()) {
            NativeImage mask = CompositeTextureBuilder.buildOutlineMask(maskSource, specs);
            try (mask) {
                // maskSource must be untouched (pure function).
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        assertPixel(maskSource, x, y, squareCanvas().getPixelRGBA(x, y),
                                "mask source untouched");
                    }
                }
                CompositeTextureBuilder.applyGunOutlines(baked, specs);
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        boolean inside = squareDistance(x, y, 2, 2, 5, 5) == 0;
                        boolean bakedStroke = !inside && FastColor.ABGR32.alpha(baked.getPixelRGBA(x, y)) > 0;
                        assertPixel(mask, x, y, bakedStroke ? WHITE : TRANSPARENT,
                                "mask pixel matches baked stroke footprint");
                    }
                }
            }
        }
    }

    @Test
    void maskCoversUnionOfNestedRings() {
        // A width-3 ring plus a width-1 ring: the mask is the UNION of both
        // strokes — every pixel at Chebyshev distance 1..3 from the square is
        // white, the interior and everything beyond stay transparent (unlike
        // the baked image, which colours the rings separately).
        List<OutlineSpec> specs = List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 3), spec(1.0F, 1.0F, 0.0F, 1.0F, 1));
        try (NativeImage mask = CompositeTextureBuilder.buildOutlineMask(
                squareCanvas(12, 12, 4, 4, 7, 7), specs)) {
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    int d = squareDistance(x, y, 4, 4, 7, 7);
                    int expected = (d >= 1 && d <= 3) ? WHITE : TRANSPARENT;
                    assertPixel(mask, x, y, expected, "union ring pixel");
                }
            }
        }
    }

    @Test
    void transparentSilhouetteYieldsEmptyMask() {
        // A fully transparent image has no alpha silhouette to stroke, so the
        // mask stays fully transparent (but non-null for a non-empty spec).
        try (NativeImage mask = CompositeTextureBuilder.buildOutlineMask(
                solid(4, 4, TRANSPARENT), List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 1)))) {
            assertNotNull(mask, "mask exists even for an empty silhouette");
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertPixel(mask, x, y, TRANSPARENT, "transparent mask pixel");
                }
            }
        }
    }

    @Test
    void singleOutlineRingsTheCompositedShape() {
        // 8x8 canvas with an opaque 4x4 white square at (2,2)-(5,5): a 2px
        // ring of outline red covers every pixel outside the square — the
        // canvas corners sit exactly at Chebyshev distance 2 — while the
        // square interior keeps its own colour. (There is no pixel farther
        // than 2 away on this canvas; the "farther pixels stay transparent"
        // property is asserted in outlinesBasedOnOriginalAlphaSnapshot with a
        // width-1 ring.)
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(img, List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 2)));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean inside = squareDistance(x, y) == 0;
                    assertPixel(img, x, y, inside ? WHITE : OUTLINE_RED,
                            inside ? "square interior" : "ring pixel");
                }
            }
        }
    }

    @Test
    void twoOutlinesNestConcentrically() {
        // Red width-3 ring + gold width-1 ring, caller-sorted widest-first:
        // gold hugs the square at distance 1, red fills distances 2-3 and the
        // square interior keeps its colour.
        List<OutlineSpec> sorted = List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 3), spec(1.0F, 1.0F, 0.0F, 1.0F, 1));
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(img, sorted);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int d = squareDistance(x, y);
                    int expected = d == 0 ? WHITE : d == 1 ? OUTLINE_GOLD : OUTLINE_RED;
                    assertPixel(img, x, y, expected, "nested ring pixel");
                }
            }
        }
        // Unsorted input [gold, red] must produce the identical result: the
        // implementation sorts by width descending before painting.
        List<OutlineSpec> shuffled = List.of(spec(1.0F, 1.0F, 0.0F, 1.0F, 1), spec(1.0F, 0.0F, 0.0F, 1.0F, 3));
        try (NativeImage img = squareCanvas();
             NativeImage reference = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(reference, sorted);
            CompositeTextureBuilder.applyGunOutlines(img, shuffled);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertPixel(img, x, y, reference.getPixelRGBA(x, y), "unsorted matches sorted");
                }
            }
        }
    }

    @Test
    void sameWidthLaterWins() {
        // Red and blue both width 2 cover the identical ring. The stable sort
        // keeps the caller's [red, blue] order, so red paints first and blue
        // paints over it — every ring pixel ends up blue.
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(img,
                    List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 2), spec(0.0F, 0.0F, 1.0F, 1.0F, 2)));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    boolean inside = squareDistance(x, y) == 0;
                    assertPixel(img, x, y, inside ? WHITE : OUTLINE_BLUE,
                            inside ? "square interior" : "ring pixel");
                }
            }
        }
    }

    @Test
    void outlinesBasedOnOriginalAlphaSnapshot() {
        // Two width-1 rings: the gold ring must be decided against the
        // ORIGINAL silhouette only. If the second layer were judged against
        // the already-painted image, the red ring (alpha 255) would widen the
        // silhouette and gold would spread to distance 2. Correct behaviour:
        // the whole ring is exactly 1 pixel wide, entirely gold, and pixels
        // two or more away stay fully transparent.
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.applyGunOutlines(img,
                    List.of(spec(1.0F, 0.0F, 0.0F, 1.0F, 1), spec(1.0F, 1.0F, 0.0F, 1.0F, 1)));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int d = squareDistance(x, y);
                    int expected = d == 0 ? WHITE : d == 1 ? OUTLINE_GOLD : TRANSPARENT;
                    assertPixel(img, x, y, expected, "snapshot-based ring pixel");
                }
            }
        }
    }

    // --- B1: source-over compositing on translucent bases ---

    @Test
    void normalMatchesLegacySourceOverOnTranslucentBase() {
        // Semi-transparent red base (255,0,0,128) with a semi-transparent
        // green source (0,255,0,128) in normal mode: standard source-over
        // gives (r,g,b,a) = (85,170,0,192). The old general formula (which
        // dropped the dstA weighting and the 1/outA normalisation) returned
        // (127,128,0,192) — the source was darkened by its own alpha.
        int out = CompositeTextureBuilder.blendPixel(
                FastColor.ABGR32.color(128, 0, 0, 255),   // base: a=128, r=255
                FastColor.ABGR32.color(128, 0, 255, 0),   // source: a=128, g=255
                OverlayBlend.NORMAL);
        assertEquals(192, FastColor.ABGR32.alpha(out), "out alpha");
        assertChannel(FastColor.ABGR32.red(out), 85, "red channel");
        assertChannel(FastColor.ABGR32.green(out), 170, "green channel");
        assertChannel(FastColor.ABGR32.blue(out), 0, "blue channel");
    }

    @Test
    void normalKeepsColorOnTransparentBase() {
        // Fully transparent base + semi-transparent green source (alpha 128)
        // in normal mode: the source must keep its full colour — the old
        // formula darkened green to 128 because it mixed C with the
        // transparent base weighted by srcA alone.
        int out = CompositeTextureBuilder.blendPixel(
                FastColor.ABGR32.color(0, 0, 0, 0),       // fully transparent base
                FastColor.ABGR32.color(128, 0, 255, 0),   // source: a=128, g=255
                OverlayBlend.NORMAL);
        assertEquals(128, FastColor.ABGR32.alpha(out), "out alpha stays 128");
        assertChannel(FastColor.ABGR32.red(out), 0, "red channel");
        assertChannel(FastColor.ABGR32.green(out), 255, "green channel keeps full intensity");
        assertChannel(FastColor.ABGR32.blue(out), 0, "blue channel");
    }

    // --- S1: tint clamping ---

    @Test
    void tintClampsOutOfRange() {
        // White × tint [2.0, -1.0, 0.5, 0.5]: red 510 and green -255 must be
        // clamped to 255 and 0. Without the clamp the bare-shift ABGR packer
        // lets red's overflow bit pollute the green byte (r=254, g=1).
        int out = tinted(WHITE, new Vector4f(2.0F, -1.0F, 0.5F, 0.5F));
        assertEquals(255, FastColor.ABGR32.red(out), "red clamped to 255");
        assertEquals(0, FastColor.ABGR32.green(out), "green clamped to 0");
        assertEquals(128, FastColor.ABGR32.blue(out), "blue halved");
        assertEquals(128, FastColor.ABGR32.alpha(out), "alpha halved");
    }

    // --- S2: outline width clamping ---

    @Test
    void outlineWidthClampedToCanvas() {
        // A width of 100 exceeds the largest Chebyshev distance on this 8x8
        // canvas (7), so it is clamped to the equivalent 7: every pixel
        // outside the square — including the corners — is painted, and the
        // result matches a width-7 run byte for byte. (Without the clamp a
        // huge width would balloon the (2w+1)² neighbourhood scan.)
        try (NativeImage img = squareCanvas();
             NativeImage reference = squareCanvas()) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 100));
            CompositeTextureBuilder.outlineLayer(reference, spec(1.0F, 0.0F, 0.0F, 1.0F, 7));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    assertPixel(img, x, y, reference.getPixelRGBA(x, y),
                            "clamped width matches width 7");
                }
            }
        }
        // Explicit shape check: the whole ring carries the stroke colour.
        try (NativeImage img = squareCanvas()) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 100));
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
    void outlineLayerIsTwoPassNoCascade() {
        // 8x8 canvas with an opaque 2x2 white square at (3,3)-(4,4) and a
        // width-2 stroke: pixels painted by the first pass must never trigger
        // a second pass, so everything farther than 2 from the square (the
        // corners sit at Chebyshev distance 3) stays fully transparent.
        NativeImage img = solid(8, 8, TRANSPARENT);
        for (int y = 3; y < 5; y++) {
            for (int x = 3; x < 5; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        try (img) {
            CompositeTextureBuilder.outlineLayer(img, spec(1.0F, 0.0F, 0.0F, 1.0F, 2));
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int d = square2Distance(x, y);
                    int expected = d == 0 ? WHITE : d <= 2 ? OUTLINE_RED : TRANSPARENT;
                    assertPixel(img, x, y, expected, "two-pass ring pixel");
                }
            }
        }
    }

    // --- outline + fit interplay ---

    @Test
    void outlinedLayerFillsWithStroke() {
        // 4x4 overlay holding a solid 2x2 green core with a transparent
        // margin, stroked with a 1px red ring along the alpha edge (the ring
        // lands on the overlay's outer row/column), then stretched over an
        // 8x8 transparent base with fit = FILL: the ring scales with the fill
        // and the canvas edge pixels — including the corners — carry the
        // stroke colour.
        NativeImage overlay = solid(4, 4, TRANSPARENT);
        for (int y = 1; y < 3; y++) {
            for (int x = 1; x < 3; x++) {
                overlay.setPixelRGBA(x, y, OVERLAY_GREEN);
            }
        }
        try (NativeImage base = solid(8, 8, TRANSPARENT); overlay) {
            CompositeTextureBuilder.outlineLayer(overlay, spec(1.0F, 0.0F, 0.0F, 1.0F, 1));
            CompositeTextureBuilder.blendOnto(base, overlay, layer(OverlayAlignment.TOP_LEFT, OverlayFit.FILL));
            assertPixel(base, 0, 0, OUTLINE_RED, "top-left corner carries stroke");
            assertPixel(base, 7, 0, OUTLINE_RED, "top-right corner carries stroke");
            assertPixel(base, 0, 7, OUTLINE_RED, "bottom-left corner carries stroke");
            assertPixel(base, 7, 7, OUTLINE_RED, "bottom-right corner carries stroke");
        }
    }

    // --- D1: Chebyshev distance transform (描边算法优化) ---------------------

    @Test
    void chebyshevTransformMarksExactDistancesFromSinglePixel() {
        // A single opaque pixel at (0,0) of a 3x3 grid: the Chebyshev distance
        // max(|dx|,|dy|) to it is 0 at the pixel, 1 at its 8 neighbours, and 2
        // nowhere on a 3x3 (all cells are within distance 1). Extend to 4x4 so
        // the far corner lands at distance 2.
        int[] alpha = new int[16];
        alpha[0] = 255; // opaque at (0,0)
        int[] dist = CompositeTextureBuilder.chebyshevDistanceTransform(alpha, 4, 4);
        int[][] expected = {
                {0, 1, 2, 3},
                {1, 1, 2, 3},
                {2, 2, 2, 3},
                {3, 3, 3, 3}
        };
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(expected[y][x], dist[y * 4 + x],
                        "Chebyshev distance at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void chebyshevTransformZeroesOpaqueAndReachesAllOnSolid() {
        // Fully transparent image: distance stays at the sentinel (>= w+h).
        int[] empty = new int[9];
        int[] dist = CompositeTextureBuilder.chebyshevDistanceTransform(empty, 3, 3);
        for (int d : dist) {
            assertTrue(d > 5, "a fully transparent snapshot yields NO valid distance (sentinel)");
        }
        // Fully opaque image: every pixel is distance 0.
        int[] opaque = new int[9];
        java.util.Arrays.fill(opaque, 255);
        int[] dist2 = CompositeTextureBuilder.chebyshevDistanceTransform(opaque, 3, 3);
        for (int d : dist2) {
            assertEquals(0, d, "opaque pixels are distance 0 everywhere");
        }
    }
}
