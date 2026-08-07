package org.yanbwe.modularshoot.client.render;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SideQuadBuilder#buildMainFaces}: the front/back
 * face geometry that carries the content rect plus the padded outline ring
 * (描边环带) — the 9-grid that lets whole-gun outlines render beyond the
 * original 16×16 canvas on the item's front and back faces.
 */
class MainFaceBuilderTest {

    private static final float FRONT_Z = 0.53125F;
    private static final float BACK_Z = 0.46875F;

    @Test
    void noPadProducesSingleFrontAndBackQuad() {
        List<SideQuad> quads = SideQuadBuilder.buildMainFaces(16, 16, 0, 16, 16);
        assertEquals(2, quads.size());

        SideQuad front = quads.get(0);
        assertEquals(0F, front.nx());
        assertEquals(0F, front.ny());
        assertEquals(1F, front.nz());
        assertEquals(FRONT_Z, front.positions()[2], 1e-6F);
        assertEquals(0.0F, front.uvs()[0], 1e-6F);
        assertEquals(1.0F, front.uvs()[2], 1e-6F);
        assertEquals(1.0F, front.uvs()[4], 1e-6F);
        assertEquals(0.0F, front.uvs()[6], 1e-6F);

        SideQuad back = quads.get(1);
        assertEquals(0F, back.nx());
        assertEquals(0F, back.ny());
        assertEquals(-1F, back.nz());
        assertEquals(BACK_Z, back.positions()[2], 1e-6F);
        // Back keeps u = +x: x=1 vertex carries u=1 (mirror from behind).
        assertEquals(1.0F, back.uvs()[0], 1e-6F);
    }

    @Test
    void padProducesNineGridPerFace() {
        // 16×16 content, pad 2 → 20×20 canvas; content rect UV [0.1,0.1]-[0.9,0.9].
        List<SideQuad> quads = SideQuadBuilder.buildMainFaces(16, 16, 2, 20, 20);
        assertEquals(18, quads.size());

        // Front face quads (normal +z): the centre one is index 0.
        SideQuad centre = quads.get(0);
        assertEquals(0.0F, centre.positions()[0], 1e-6F);
        assertEquals(1.0F, centre.positions()[3], 1e-6F);
        assertEquals(0.1F, centre.uvs()[0], 1e-6F); // uMin at x=0
        assertEquals(0.9F, centre.uvs()[2], 1e-6F); // uMax at x=1
        assertEquals(0.9F, centre.uvs()[1], 1e-6F); // v at y=0 is vMax = 0.9
        assertEquals(0.1F, centre.uvs()[5], 1e-6F); // v at y=1 is vMin = 0.1

        // Top band: y ∈ [1, 1.125], u ∈ [0.1, 0.9], v ∈ [0, 0.1].
        // Quad order interleaves front/back per grid cell: index 2 is the
        // front top band (cells: 0 centre, 1 top, 2 bottom, 3 left, ...).
        SideQuad top = quads.get(2);
        assertEquals(1.0F, top.positions()[1], 1e-6F);
        assertEquals(1.0F + 2.0F / 16.0F, top.positions()[7], 1e-6F);
        assertEquals(0.1F, top.uvs()[1], 1e-6F);   // content top (y=1) → v = v0
        assertEquals(0.0F, top.uvs()[5], 1e-6F);   // canvas top (y=1.125) → v = 0

        // Bottom band: y ∈ [-0.125, 0], v ∈ [0.9, 1].
        SideQuad bottom = quads.get(4);
        assertEquals(-2.0F / 16.0F, bottom.positions()[1], 1e-6F);
        assertEquals(1.0F, bottom.uvs()[1], 1e-6F);  // canvas bottom → v = 1
        assertEquals(0.9F, bottom.uvs()[5], 1e-6F);  // content bottom (y=0) → v = v1

        // Left band: x ∈ [-0.125, 0], u ∈ [0, 0.1].
        SideQuad left = quads.get(6);
        assertEquals(-2.0F / 16.0F, left.positions()[0], 1e-6F);
        assertEquals(0.0F, left.uvs()[0], 1e-6F);
        assertEquals(0.1F, left.uvs()[2], 1e-6F);
    }

    @Test
    void frontGridTilesTheWholeCanvasWithoutGaps() {
        // Sum of the front-face quad areas (in UV space) must equal the full
        // canvas area 1 — the 9-grid tiles the padded canvas contiguously.
        List<SideQuad> quads = SideQuadBuilder.buildMainFaces(8, 8, 1, 10, 10);
        double area = 0;
        int frontCount = 0;
        for (SideQuad q : quads) {
            if (q.nz() != 1F) {
                continue;
            }
            frontCount++;
            float u0 = Math.min(q.uvs()[0], q.uvs()[4]);
            float u1 = Math.max(q.uvs()[0], q.uvs()[4]);
            float v0 = Math.min(q.uvs()[1], q.uvs()[5]);
            float v1 = Math.max(q.uvs()[1], q.uvs()[5]);
            area += (u1 - u0) * (v1 - v0);
        }
        assertEquals(9, frontCount);
        assertEquals(1.0, area, 1e-6);
    }

    @Test
    void backQuadsKeepFrontUAssignmentForMirror() {
        // Back-face quad u must run along +x like the front face: the left
        // band's low-x edge carries u = uMin.
        List<SideQuad> quads = SideQuadBuilder.buildMainFaces(16, 16, 2, 20, 20);
        // Back quads are interleaved after each front quad; the back left
        // band is index 2*3 + 1 = 7.
        SideQuad backLeft = quads.get(7);
        assertEquals(-1F, backLeft.nz());
        // Back vertex order starts at the high-x edge: v0 = (0, 0) (right
        // edge of the band), v1 = (-0.125, 0) (left edge).
        assertEquals(0.0F, backLeft.positions()[0], 1e-6F);
        assertEquals(-2.0F / 16.0F, backLeft.positions()[3], 1e-6F);
        assertEquals(0.1F, backLeft.uvs()[0], 1e-6F);  // x=0 ↔ u = u0 = 0.1
        assertEquals(0.0F, backLeft.uvs()[2], 1e-6F);  // x=-0.125 ↔ u = 0
        assertEquals(BACK_Z, backLeft.positions()[2], 1e-6F);
    }
}
