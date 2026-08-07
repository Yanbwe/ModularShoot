package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.FastColor;

/**
 * Generates the thin side quads hugging a composited texture's alpha
 * silhouette, replicating the span scan of vanilla's
 * {@code ItemModelGenerator.createSideElements} (see the vanilla sources:
 * {@code getSpans} / {@code checkTransition} / {@code createOrExpandSpan}).
 *
 * <p>Every opaque pixel whose neighbour in a given direction is transparent
 * (out-of-canvas counts as transparent) starts or extends a span along the
 * silhouette edge; contiguous runs merge into a single span. Each span
 * becomes one zero-thickness quad of one-pixel depth in the item's z range
 * (7.5..8.5 in the 16-grid, i.e. z ∈ [0.46875, 0.53125] in baked model
 * space), showing the corresponding 1px edge strip of the texture — the
 * vanilla flat-item extrusion look.</p>
 *
 * <p><b>Coordinate split:</b> model coordinates are expressed in
 * <em>content space</em> (content pixel minus content origin, divided by
 * content dimensions) so side quads stay glued to the main quad's 0..1
 * geometry regardless of canvas padding; UVs are in <em>canvas space</em>
 * (canvas pixel divided by canvas dimensions) so they sample the actual
 * uploaded image. With an unpadded canvas (content origin 0, content size
 * == canvas size) both spaces coincide.</p>
 *
 * <p>Vertex order and UV assignment follow {@code FaceInfo} and
 * {@code BlockFaceUV} (rotation 0), so the quads face outward with the same
 * winding as vanilla baked quads. Pure function — no Minecraft client state
 * is touched, unit-testable off the render thread.</p>
 *
 * @see SideQuad
 * @see CompositeTextureBuilder#applyGunOutlines
 */
public final class SideQuadBuilder {

    private SideQuadBuilder() {
    }

    /** Span facing: UP/DOWN hug horizontal rows, LEFT/RIGHT hug vertical columns. */
    private enum Facing {
        UP, DOWN, LEFT, RIGHT
    }

    /**
     * A contiguous run of silhouette-edge pixels (vanilla
     * {@code ItemModelGenerator.Span}).
     */
    private static final class Span {
        final Facing facing;
        int min;
        int max;
        final int anchor;

        Span(Facing facing, int min, int max, int anchor) {
            this.facing = facing;
            this.min = min;
            this.max = max;
            this.anchor = anchor;
        }

        void expand(int value) {
            if (value < this.min) {
                this.min = value;
            } else if (value > this.max) {
                this.max = value;
            }
        }
    }

    /**
     * Scans the canvas for silhouette edges and builds the side quads.
     *
     * @param image     the composited canvas image (gun outlines already
     *                  baked when present); must not be {@code null}
     * @param contentX  x of the content region's origin within the canvas
     * @param contentY  y of the content region's origin within the canvas
     * @param contentW  content width in pixels (model coordinates divide by
     *                  this)
     * @param contentH  content height in pixels
     * @return the side quads, in span order (UP/DOWN/LEFT/RIGHT scan order);
     *         empty when the image has no opaque pixels
     */
    public static List<SideQuad> build(NativeImage image,
                                       int contentX, int contentY, int contentW, int contentH) {
        int canvasW = image.getWidth();
        int canvasH = image.getHeight();
        List<Span> spans = new ArrayList<>();
        for (int y = 0; y < canvasH; y++) {
            for (int x = 0; x < canvasW; x++) {
                boolean opaque = FastColor.ABGR32.alpha(image.getPixelRGBA(x, y)) > 0;
                checkTransition(spans, Facing.UP, x, y, opaque,
                        alphaAt(image, x, y - 1, canvasW, canvasH));
                checkTransition(spans, Facing.DOWN, x, y, opaque,
                        alphaAt(image, x, y + 1, canvasW, canvasH));
                checkTransition(spans, Facing.LEFT, x, y, opaque,
                        alphaAt(image, x - 1, y, canvasW, canvasH));
                checkTransition(spans, Facing.RIGHT, x, y, opaque,
                        alphaAt(image, x + 1, y, canvasW, canvasH));
            }
        }
        List<SideQuad> quads = new ArrayList<>(spans.size());
        for (Span span : spans) {
            quads.add(toQuad(span, contentX, contentY, contentW, contentH, canvasW, canvasH));
        }
        return quads;
    }

    /**
     * Reads the alpha of a canvas pixel, treating out-of-canvas as
     * transparent — identical to vanilla {@code ItemModelGenerator.isTransparent}.
     */
    private static int alphaAt(NativeImage image, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return 0;
        }
        return FastColor.ABGR32.alpha(image.getPixelRGBA(x, y));
    }

    /**
     * Starts or extends a span when the pixel is opaque and its neighbour in
     * the span's direction is transparent (vanilla
     * {@code checkTransition} + {@code createOrExpandSpan}).
     */
    private static void checkTransition(List<Span> spans, Facing facing,
                                        int x, int y, boolean opaque, int neighbourAlpha) {
        if (!opaque || neighbourAlpha > 0) {
            return;
        }
        // Primary axis: x for horizontal spans, y for vertical ones.
        int primary = facing == Facing.UP || facing == Facing.DOWN ? x : y;
        int anchor = facing == Facing.UP || facing == Facing.DOWN ? y : x;
        Span match = null;
        for (Span span : spans) {
            if (span.facing == facing && span.anchor == anchor) {
                match = span;
                break;
            }
        }
        if (match == null) {
            spans.add(new Span(facing, primary, primary, anchor));
        } else {
            match.expand(primary);
        }
    }

    /**
     * Builds the front and back face quads of the item: the content rect
     * plus the padded outline ring (描边环带).
     *
     * <p>The main faces sample the content rect of the canvas; when the
     * canvas is padded (gun outlines need room outside the silhouette) the
     * ring between the content rect and the canvas border is covered by 8
     * additional quads per face — top/bottom/left/right bands and the four
     * corners — so outline pixels beyond the original content canvas render
     * on the item's front and back, not just on the side edges. Model
     * coordinates are in content space (may exceed 0..1), UVs in canvas
     * space. The back face keeps the front's u = +x assignment so it reads
     * mirrored from behind (vanilla NORTH face behaviour).</p>
     *
     * <p>With an unpadded canvas ({@code pad == 0}) exactly two quads are
     * returned: the plain front and back faces spanning the full canvas.</p>
     *
     * @param contentW content width in pixels
     * @param contentH content height in pixels
     * @param pad      canvas padding on every side in pixels
     * @param canvasW  padded canvas width in pixels
     * @param canvasH  padded canvas height in pixels
     * @return the front and back face quads, front first, interleaved
     *         front/back per grid cell
     */
    public static List<SideQuad> buildMainFaces(int contentW, int contentH, int pad,
                                                int canvasW, int canvasH) {
        if (pad == 0) {
            return List.of(
                    mainQuad(0F, 1F, 0F, 1F, 0F, 1F, 0F, 1F, 0.53125F, 0F, 0F, 1F),
                    mainQuad(1F, 0F, 0F, 1F, 1F, 0F, 0F, 1F, 0.46875F, 0F, 0F, -1F));
        }
        float pw = (float) pad / contentW;
        float ph = (float) pad / contentH;
        float u0 = (float) pad / canvasW;
        float u1 = (float) (pad + contentW) / canvasW;
        float v0 = (float) pad / canvasH;
        float v1 = (float) (pad + contentH) / canvasH;
        // Grid cells as {x0, x1, y0, y1, uMin, uMax, vMin, vMax}: centre,
        // top band, bottom band, left band, right band, then the four
        // corners. vMin is the top edge (canvas v = 0 at the very top).
        float[][] regions = {
                {0F, 1F, 0F, 1F, u0, u1, v0, v1},
                {0F, 1F, 1F, 1F + ph, u0, u1, 0F, v0},
                {0F, 1F, -ph, 0F, u0, u1, v1, 1F},
                {-pw, 0F, 0F, 1F, 0F, u0, v0, v1},
                {1F, 1F + pw, 0F, 1F, u1, 1F, v0, v1},
                {-pw, 0F, 1F, 1F + ph, 0F, u0, 0F, v0},
                {1F, 1F + pw, 1F, 1F + ph, u1, 1F, 0F, v0},
                {-pw, 0F, -ph, 0F, 0F, u0, v1, 1F},
                {1F, 1F + pw, -ph, 0F, u1, 1F, v1, 1F},
        };
        List<SideQuad> quads = new ArrayList<>(regions.length * 2);
        for (float[] r : regions) {
            quads.add(mainQuad(r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], 0.53125F, 0F, 0F, 1F));
            // Back face: x runs the same way as u (mirror from behind), so
            // swap the x extent and the u extent together.
            quads.add(mainQuad(r[1], r[0], r[2], r[3], r[5], r[4], r[6], r[7], 0.46875F, 0F, 0F, -1F));
        }
        return quads;
    }

    /**
     * Builds a single front/back face quad for one grid cell.
     *
     * <p>Vertex order for the front face: v0=(x0,y0)uv(uMin,vMax),
     * v1=(x1,y0)uv(uMax,vMax), v2=(x1,y1)uv(uMax,vMin),
     * v3=(x0,y1)uv(uMin,vMin) — u along +x, v = 1 - y within the cell.
     * The back face passes swapped x and u extents so u still runs along
     * +x (the mirrored-from-behind look).</p>
     */
    private static SideQuad mainQuad(float x0, float x1, float y0, float y1,
                                     float uMin, float uMax, float vMin, float vMax,
                                     float z, float nx, float ny, float nz) {
        return new SideQuad(
                new float[]{x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z},
                new float[]{uMin, vMax, uMax, vMax, uMax, vMin, uMin, vMin},
                nx, ny, nz);
    }

    /**
     * Converts a span into a zero-thickness side quad.
     *
     * <p>Model coordinates (baked space 0..1) use content space; UVs use
     * canvas space. The vertex order and UV assignment mirror vanilla
     * {@code FaceInfo} vertex tables combined with {@code BlockFaceUV}
     * (rotation 0).</p>
     */
    private static SideQuad toQuad(Span span,
                                   int contentX, int contentY, int contentW, int contentH,
                                   int canvasW, int canvasH) {
        float s = span.min;
        float t = span.max;
        float anchor = span.anchor;
        float z0 = 0.46875F;
        float z1 = 0.53125F;

        // Content-space model coordinates.
        // UP/DOWN span horizontally (s..t on x) pinned at the anchor row;
        // LEFT/RIGHT span vertically (s..t on y) pinned at the anchor column.
        float x0 = (s - contentX) / contentW;
        float x1 = (t + 1.0F - contentX) / contentW;
        float anchorX0 = (anchor - contentX) / contentW;
        float anchorX1 = (anchor + 1.0F - contentX) / contentW;
        float yHi = 1.0F - (s - contentY) / contentH;
        float yLo = 1.0F - (t + 1.0F - contentY) / contentH;
        float anchorY = 1.0F - (anchor - contentY) / contentH;
        float anchorY1 = 1.0F - (anchor + 1.0F - contentY) / contentH;

        // Canvas-space UVs.
        // UP/DOWN sample the anchor row (v), the full span width (u);
        // LEFT/RIGHT sample the anchor column (u), the full span height (v).
        float u0 = s / canvasW;
        float u1 = (t + 1.0F) / canvasW;
        float v0 = anchor / canvasH;
        float v1 = (anchor + 1.0F) / canvasH;
        float uAnchor0 = anchor / canvasW;
        float uAnchor1 = (anchor + 1.0F) / canvasW;
        float vSpan0 = s / canvasH;
        float vSpan1 = (t + 1.0F) / canvasH;

        float[] positions;
        float[] uvs;
        float nx;
        float ny;
        float nz;
        switch (span.facing) {
            case UP -> {
                positions = new float[]{
                        x0, anchorY, z0, x0, anchorY, z1, x1, anchorY, z1, x1, anchorY, z0};
                uvs = new float[]{u0, v0, u0, v1, u1, v1, u1, v0};
                nx = 0F;
                ny = 1F;
                nz = 0F;
            }
            case DOWN -> {
                positions = new float[]{
                        x0, anchorY1, z1, x0, anchorY1, z0, x1, anchorY1, z0, x1, anchorY1, z1};
                uvs = new float[]{u0, v0, u0, v1, u1, v1, u1, v0};
                nx = 0F;
                ny = -1F;
                nz = 0F;
            }
            case LEFT -> {
                positions = new float[]{
                        anchorX0, yHi, z0, anchorX0, yLo, z0, anchorX0, yLo, z1, anchorX0, yHi, z1};
                uvs = new float[]{uAnchor0, vSpan0, uAnchor0, vSpan1, uAnchor1, vSpan1, uAnchor1, vSpan0};
                nx = -1F;
                ny = 0F;
                nz = 0F;
            }
            default -> { // RIGHT
                positions = new float[]{
                        anchorX1, yHi, z1, anchorX1, yLo, z1, anchorX1, yLo, z0, anchorX1, yHi, z0};
                uvs = new float[]{uAnchor0, vSpan0, uAnchor0, vSpan1, uAnchor1, vSpan1, uAnchor1, vSpan0};
                nx = 1F;
                ny = 0F;
                nz = 0F;
            }
        }
        return new SideQuad(positions, uvs, nx, ny, nz);
    }
}
