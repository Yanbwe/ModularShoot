package org.yanbwe.modularshoot.client;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.render.CompositeTextureBuilder.OverlayLayer;
import org.yanbwe.modularshoot.client.render.DynamicGunTextureCache;
import org.yanbwe.modularshoot.plugin.OutlineSpec;
import org.yanbwe.modularshoot.plugin.OverlayAlignment;
import org.yanbwe.modularshoot.plugin.OverlayBlend;
import org.yanbwe.modularshoot.plugin.OverlayFit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Value-semantics tests for {@link DynamicGunTextureCache.Key} (审查优化 P5:
 * 缓存键相等性依赖 JOML 向量对象身份 → 值语义).
 *
 * <p>JOML {@link Vector4f}/{@link Vector3f} do not override
 * {@code equals}/{@code hashCode}, so a naive record key would compare tint /
 * outline colour by object identity: any re-decoded definition instance would
 * miss the cache and force a full per-frame PNG decode + composite + GPU
 * upload. These tests pin the hand-written value comparison: equal float
 * values in distinct vector instances must compare equal, and differing
 * values must compare unequal.</p>
 */
class DynamicGunTextureCacheKeyTest {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.parse("modularshoot:textures/gun/test.png");

    private static OverlayLayer layer(float r, float g, float b, float a) {
        return new OverlayLayer(
                ResourceLocation.parse("modularshoot:textures/plugin/overlay.png"),
                OverlayAlignment.CENTER, OverlayFit.CONTAIN,
                new Vector4f(r, g, b, a),
                OverlayBlend.MULTIPLY,
                Optional.empty());
    }

    private static OutlineSpec outline(float r, float g, float b) {
        return new OutlineSpec(new Vector3f(r, g, b), 0.5f, 2);
    }

    private static DynamicGunTextureCache.Key key(
            List<OverlayLayer> overlays, List<OutlineSpec> outlines) {
        return new DynamicGunTextureCache.Key(TEXTURE, overlays, outlines, 3);
    }

    @Test
    void keysWithEqualTintValuesInDistinctVectorsAreEqual() {
        DynamicGunTextureCache.Key a = key(List.of(layer(0.5f, 0.25f, 1.0f, 0.9f)), List.of());
        DynamicGunTextureCache.Key b = key(List.of(layer(0.5f, 0.25f, 1.0f, 0.9f)), List.of());
        assertEquals(a, b, "equal float tint values in distinct Vector4f instances must compare equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void keysWithDifferentTintValuesAreUnequal() {
        DynamicGunTextureCache.Key a = key(List.of(layer(0.5f, 0.25f, 1.0f, 0.9f)), List.of());
        DynamicGunTextureCache.Key b = key(List.of(layer(0.6f, 0.25f, 1.0f, 0.9f)), List.of());
        assertNotEquals(a, b, "different tint values must compare unequal");
    }

    @Test
    void keysWithEqualOutlineColorsInDistinctVectorsAreEqual() {
        DynamicGunTextureCache.Key a = key(List.of(), List.of(outline(0.1f, 0.2f, 0.3f)));
        DynamicGunTextureCache.Key b = key(List.of(), List.of(outline(0.1f, 0.2f, 0.3f)));
        assertEquals(a, b, "equal outline color values in distinct Vector3f instances must compare equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void keysWithDifferentOutlineColorsAreUnequal() {
        DynamicGunTextureCache.Key a = key(List.of(), List.of(outline(0.1f, 0.2f, 0.3f)));
        DynamicGunTextureCache.Key b = key(List.of(), List.of(outline(0.9f, 0.2f, 0.3f)));
        assertNotEquals(a, b, "different outline colors must compare unequal");
    }

    @Test
    void keysDifferByModifierVersionOrTexture() {
        DynamicGunTextureCache.Key a = key(List.of(), List.of());
        DynamicGunTextureCache.Key b = new DynamicGunTextureCache.Key(TEXTURE, List.of(), List.of(), 4);
        assertNotEquals(a, b, "modifier version is part of the key");
        DynamicGunTextureCache.Key c = new DynamicGunTextureCache.Key(
                ResourceLocation.parse("modularshoot:textures/gun/other.png"), List.of(), List.of(), 3);
        assertNotEquals(a, c, "texture path is part of the key");
    }
}
