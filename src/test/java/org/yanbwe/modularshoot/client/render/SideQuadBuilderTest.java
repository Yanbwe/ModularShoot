package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.List;
import net.minecraft.util.FastColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SideQuadBuilder}: span detection along the alpha
 * silhouette, span merging and the content-space / canvas-space coordinate
 * split used by the padded-outline canvas.
 */
class SideQuadBuilderTest {

    private static final int TRANSPARENT = FastColor.ABGR32.color(0, 0, 0, 0);
    private static final int WHITE = FastColor.ABGR32.color(255, 255, 255, 255);

    /** Builds a w×h image filled with the given ABGR pixel. */
    private static NativeImage solid(int w, int h, int rgba) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, rgba);
            }
        }
        return img;
    }

    /** Convenience overload: content == full canvas. */
    private static List<SideQuad> buildSimple(NativeImage img) {
        return SideQuadBuilder.build(img, 0, 0, img.getWidth(), img.getHeight());
    }

    private static SideQuad firstWithNormal(List<SideQuad> quads, float nx, float ny, float nz) {
        return quads.stream()
                .filter(q -> q.nx() == nx && q.ny() == ny && q.nz() == nz)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no quad with normal (" + nx + ", " + ny + ", " + nz + ")"));
    }

    private static void assertVertex(SideQuad q, int index,
                                     float x, float y, float z, float u, float v) {
        assertEquals(x, q.positions()[index * 3], 1e-6F, "vertex " + index + " x");
        assertEquals(y, q.positions()[index * 3 + 1], 1e-6F, "vertex " + index + " y");
        assertEquals(z, q.positions()[index * 3 + 2], 1e-6F, "vertex " + index + " z");
        assertEquals(u, q.uvs()[index * 2], 1e-6F, "vertex " + index + " u");
        assertEquals(v, q.uvs()[index * 2 + 1], 1e-6F, "vertex " + index + " v");
    }

    @Test
    void fullOpaqueSquareGeneratesFourEdgeQuads() {
        NativeImage img = solid(16, 16, WHITE);
        List<SideQuad> quads = buildSimple(img);
        assertEquals(4, quads.size());

        SideQuad up = firstWithNormal(quads, 0, 1, 0);
        assertVertex(up, 0, 0.0F, 1.0F, 0.46875F, 0.0F, 0.0F);
        assertVertex(up, 1, 0.0F, 1.0F, 0.53125F, 0.0F, 1.0F / 16.0F);
        assertVertex(up, 2, 1.0F, 1.0F, 0.53125F, 1.0F, 1.0F / 16.0F);
        assertVertex(up, 3, 1.0F, 1.0F, 0.46875F, 1.0F, 0.0F);

        SideQuad down = firstWithNormal(quads, 0, -1, 0);
        assertVertex(down, 0, 0.0F, 0.0F, 0.53125F, 0.0F, 15.0F / 16.0F);
        assertVertex(down, 1, 0.0F, 0.0F, 0.46875F, 0.0F, 1.0F);
        assertVertex(down, 2, 1.0F, 0.0F, 0.46875F, 1.0F, 1.0F);
        assertVertex(down, 3, 1.0F, 0.0F, 0.53125F, 1.0F, 15.0F / 16.0F);

        SideQuad left = firstWithNormal(quads, -1, 0, 0);
        assertVertex(left, 0, 0.0F, 1.0F, 0.46875F, 0.0F, 0.0F);
        assertVertex(left, 1, 0.0F, 0.0F, 0.46875F, 0.0F, 1.0F);
        assertVertex(left, 2, 0.0F, 0.0F, 0.53125F, 1.0F / 16.0F, 1.0F);
        assertVertex(left, 3, 0.0F, 1.0F, 0.53125F, 1.0F / 16.0F, 0.0F);

        SideQuad right = firstWithNormal(quads, 1, 0, 0);
        assertVertex(right, 0, 1.0F, 1.0F, 0.53125F, 15.0F / 16.0F, 0.0F);
        assertVertex(right, 1, 1.0F, 0.0F, 0.53125F, 15.0F / 16.0F, 1.0F);
        assertVertex(right, 2, 1.0F, 0.0F, 0.46875F, 1.0F, 1.0F);
        assertVertex(right, 3, 1.0F, 1.0F, 0.46875F, 1.0F, 0.0F);
    }

    @Test
    void centralSquareGeneratesEdgesAroundSilhouetteOnly() {
        // 8×8 canvas with an opaque 4×4 white square at (2,2)-(5,5);
        // every other pixel is explicitly transparent.
        NativeImage img = solid(8, 8, TRANSPARENT);
        for (int y = 2; y < 6; y++) {
            for (int x = 2; x < 6; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        List<SideQuad> quads = buildSimple(img);
        assertEquals(4, quads.size());

        SideQuad up = firstWithNormal(quads, 0, 1, 0);
        assertVertex(up, 0, 2.0F / 8.0F, 0.75F, 0.46875F, 2.0F / 8.0F, 2.0F / 8.0F);
        assertVertex(up, 1, 2.0F / 8.0F, 0.75F, 0.53125F, 2.0F / 8.0F, 3.0F / 8.0F);
        assertVertex(up, 2, 6.0F / 8.0F, 0.75F, 0.53125F, 6.0F / 8.0F, 3.0F / 8.0F);
        assertVertex(up, 3, 6.0F / 8.0F, 0.75F, 0.46875F, 6.0F / 8.0F, 2.0F / 8.0F);

        SideQuad down = firstWithNormal(quads, 0, -1, 0);
        assertVertex(down, 0, 2.0F / 8.0F, 0.25F, 0.53125F, 2.0F / 8.0F, 5.0F / 8.0F);

        SideQuad left = firstWithNormal(quads, -1, 0, 0);
        assertVertex(left, 0, 0.25F, 0.75F, 0.46875F, 2.0F / 8.0F, 2.0F / 8.0F);
        assertVertex(left, 2, 0.25F, 0.25F, 0.53125F, 3.0F / 8.0F, 6.0F / 8.0F);

        SideQuad right = firstWithNormal(quads, 1, 0, 0);
        assertVertex(right, 0, 0.75F, 0.75F, 0.53125F, 5.0F / 8.0F, 2.0F / 8.0F);
        assertVertex(right, 2, 0.75F, 0.25F, 0.46875F, 6.0F / 8.0F, 6.0F / 8.0F);
    }

    @Test
    void fullyTransparentProducesNoQuads() {
        NativeImage img = solid(8, 8, TRANSPARENT);
        assertTrue(buildSimple(img).isEmpty());
    }

    @Test
    void contiguousRowMergesIntoSingleSpan() {
        // 8×8 with only row y=3 opaque — the row must merge into one UP span
        // (and one DOWN span), not eight separate quads.
        NativeImage img = solid(8, 8, TRANSPARENT);
        for (int x = 0; x < 8; x++) {
            img.setPixelRGBA(x, 3, WHITE);
        }
        List<SideQuad> quads = buildSimple(img);
        assertEquals(4, quads.size());

        SideQuad up = firstWithNormal(quads, 0, 1, 0);
        assertVertex(up, 0, 0.0F, 0.625F, 0.46875F, 0.0F, 3.0F / 8.0F);
        assertVertex(up, 2, 1.0F, 0.625F, 0.53125F, 1.0F, 4.0F / 8.0F);

        SideQuad down = firstWithNormal(quads, 0, -1, 0);
        assertVertex(down, 0, 0.0F, 0.5F, 0.53125F, 0.0F, 3.0F / 8.0F);
        assertVertex(down, 2, 1.0F, 0.5F, 0.46875F, 1.0F, 4.0F / 8.0F);
    }

    @Test
    void ringSilhouetteGeneratesOuterAndInnerEdges() {
        // 8×8 ring: outer frame (x/y == 0 or 7) plus inner frame (x/y == 3 or 4).
        NativeImage img = solid(8, 8, TRANSPARENT);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (x == 0 || x == 7 || y == 0 || y == 7 || x == 3 || x == 4 || y == 3 || y == 4) {
                    img.setPixelRGBA(x, y, WHITE);
                }
            }
        }
        List<SideQuad> quads = buildSimple(img);

        // Outer frame top row (anchor 0) and bottom row (anchor 7, its upper
        // neighbour is transparent) both produce UP spans...
        assertTrue(hasUpQuadAt(quads, 1.0F), "outer frame top edge UP span missing");
        assertTrue(hasUpQuadAt(quads, 1.0F - 7.0F / 8.0F), "outer frame bottom edge UP span missing");
        // ...and the inner frame's top row (anchor 3) produces an UP span too.
        assertTrue(hasUpQuadAt(quads, 1.0F - 3.0F / 8.0F), "inner frame top edge UP span missing");
    }

    private static boolean hasUpQuadAt(List<SideQuad> quads, float y) {
        return quads.stream().anyMatch(q ->
                q.ny() == 1.0F && Math.abs(q.positions()[1] - y) < 1e-6F);
    }

    @Test
    void paddedCanvasUsesContentCoordinatesAndCanvasUvs() {
        // 12×12 canvas with a pad of 2: opaque 4×4 square at (3,2)-(6,5),
        // content region is the 8×8 rect at origin (2,2). The square's
        // anchor rows/columns differ from its span extents, so any mix-up
        // between the anchor axis and the span axis shows up as a wrong
        // coordinate.
        NativeImage img = solid(12, 12, TRANSPARENT);
        for (int y = 2; y < 6; y++) {
            for (int x = 3; x < 7; x++) {
                img.setPixelRGBA(x, y, WHITE);
            }
        }
        List<SideQuad> quads = SideQuadBuilder.build(img, 2, 2, 8, 8);
        assertEquals(4, quads.size());

        // UP: anchor row 2 → y = 1 - 0/8 = 1.0; span x ∈ [3,6] →
        // content x ∈ [0.125, 0.625]; canvas UVs u ∈ [3/12, 7/12],
        // v ∈ [2/12, 3/12].
        SideQuad up = firstWithNormal(quads, 0, 1, 0);
        assertVertex(up, 0, 0.125F, 1.0F, 0.46875F, 3.0F / 12.0F, 2.0F / 12.0F);
        assertVertex(up, 1, 0.125F, 1.0F, 0.53125F, 3.0F / 12.0F, 3.0F / 12.0F);
        assertVertex(up, 2, 0.625F, 1.0F, 0.53125F, 7.0F / 12.0F, 3.0F / 12.0F);
        assertVertex(up, 3, 0.625F, 1.0F, 0.46875F, 7.0F / 12.0F, 2.0F / 12.0F);

        // DOWN: anchor row 5 → y = 1 - 4/8 = 0.5.
        SideQuad down = firstWithNormal(quads, 0, -1, 0);
        assertVertex(down, 0, 0.125F, 0.5F, 0.53125F, 3.0F / 12.0F, 5.0F / 12.0F);
        assertVertex(down, 2, 0.625F, 0.5F, 0.46875F, 7.0F / 12.0F, 6.0F / 12.0F);

        // LEFT: anchor column 3 → x = 1/8 = 0.125; span y ∈ [2,5] →
        // y ∈ [0.5, 1.0]; canvas UVs u ∈ [3/12, 4/12], v ∈ [2/12, 6/12].
        SideQuad left = firstWithNormal(quads, -1, 0, 0);
        assertVertex(left, 0, 0.125F, 1.0F, 0.46875F, 3.0F / 12.0F, 2.0F / 12.0F);
        assertVertex(left, 1, 0.125F, 0.5F, 0.46875F, 3.0F / 12.0F, 6.0F / 12.0F);
        assertVertex(left, 2, 0.125F, 0.5F, 0.53125F, 4.0F / 12.0F, 6.0F / 12.0F);
        assertVertex(left, 3, 0.125F, 1.0F, 0.53125F, 4.0F / 12.0F, 2.0F / 12.0F);

        // RIGHT: anchor column 6 → x = 5/8 = 0.625.
        SideQuad right = firstWithNormal(quads, 1, 0, 0);
        assertVertex(right, 0, 0.625F, 1.0F, 0.53125F, 6.0F / 12.0F, 2.0F / 12.0F);
        assertVertex(right, 1, 0.625F, 0.5F, 0.53125F, 6.0F / 12.0F, 6.0F / 12.0F);
        assertVertex(right, 2, 0.625F, 0.5F, 0.46875F, 7.0F / 12.0F, 6.0F / 12.0F);
        assertVertex(right, 3, 0.625F, 1.0F, 0.46875F, 7.0F / 12.0F, 2.0F / 12.0F);
    }
}
