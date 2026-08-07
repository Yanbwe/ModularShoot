package org.yanbwe.modularshoot.client.render;

/**
 * A single thin side quad hugging a silhouette edge, replicating the spans
 * produced by vanilla's {@code ItemModelGenerator.createSideElements}.
 *
 * <p>Coordinates are in baked model space (0..1, z between 0.46875 and
 * 0.53125 — the item's 1px thickness), expressed in <em>content space</em>
 * (content pixels divided by content dimensions); UVs are in 0..1 in
 * <em>canvas space</em> (canvas pixels divided by canvas dimensions), with
 * v = 0 at the texture top — matching {@link DynamicItemModelRenderer}.
 * When the canvas is not padded the two spaces coincide.</p>
 *
 * <p>Immutable value object; created by {@link SideQuadBuilder}.</p>
 *
 * @param positions 12 floats: (x1,y1,z1, x2,y2,z2, x3,y3,z3, x4,y4,z4)
 * @param uvs       8 floats: (u1,v1, u2,v2, u3,v3, u4,v4)
 * @param nx        face normal x
 * @param ny        face normal y
 * @param nz        face normal z
 * @see SideQuadBuilder
 */
public record SideQuad(float[] positions, float[] uvs, float nx, float ny, float nz) {
}
