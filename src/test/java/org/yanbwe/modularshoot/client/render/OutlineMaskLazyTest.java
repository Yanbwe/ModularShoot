package org.yanbwe.modularshoot.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.render.DynamicGunTextureCache.Key;
import org.yanbwe.modularshoot.plugin.OutlineSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for 阶段 4 / 任务 4.3: 描边 mask 懒生成与算法优化.
 *
 * <p>Three behaviours are pinned here:</p>
 * <ul>
 *   <li><b>懒生成决策</b> — {@link DynamicGunTextureCache} must only build /
 *       upload the outline mask when {@link DynamicOutlineTintRegistry}
 *       actually holds a provider for one of the gun's outline-carrying
 *       plugins. With no provider, a non-empty {@code gunOutlines} list must
 *       NOT produce a mask (no redundant GPU upload); with a provider it must.
 *       The real GPU upload path needs a live Minecraft client, so the
 *       headless test pins the pure decision predicate used to gate it.</li>
 *   <li><b>注册表存在性查询</b> — {@link DynamicOutlineTintRegistry} exposes a
 *       cheap existence check (no provider invocation) that the cache uses for
 *       the decision.</li>
 *   <li><b>算法一致性</b> — the optimised {@link CompositeTextureBuilder}
 *       outline path (distance-transform / two-pass, O(W·H) instead of
 *       O(W·H·width²)) must produce byte-identical stroke pixels to the
 *       previous neighbourhood-scan algorithm on representative shapes and
 *       width sets, so the visual result is unchanged.</li>
 * </ul>
 *
 * <p>Same headless JUnit bridge as {@code DynamicGunTextureCacheKeyTest}
 * (bootstrap the vanilla registries).</p>
 */
class OutlineMaskLazyTest {

    private static final ResourceLocation TEXTURE = ResourceLocation.parse("modularshoot:gun");
    private static final ResourceLocation PLUGIN = ResourceLocation.parse("modularshoot:outline_plugin");
    private static final ResourceLocation OTHER_PLUGIN = ResourceLocation.parse("modularshoot:other_plugin");

    private static final int TRANSPARENT = FastColor.ABGR32.color(0, 0, 0, 0);
    private static final int WHITE = FastColor.ABGR32.color(255, 255, 255, 255);

    static {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void clearRegistry() {
        DynamicOutlineTintRegistry.clear();
    }

    // ---- helpers ----------------------------------------------------------

    private static OutlineSpec spec(float r, float g, float b, int width) {
        return new OutlineSpec(new Vector3f(r, g, b), 1.0F, width);
    }

    private static Key key(List<OutlineSpec> outlines) {
        return new Key(TEXTURE, List.of(), outlines, 0);
    }

    /** Builds a w×h transparent canvas with an L-shaped opaque silhouette. */
    private static NativeImage lShapeCanvas(int w, int h) {
        NativeImage img = solid(w, h, TRANSPARENT);
        for (int x = 3; x <= w - 2; x++) {
            img.setPixelRGBA(x, 2, WHITE);
        }
        for (int y = 2; y <= h - 2; y++) {
            img.setPixelRGBA(4, y, WHITE);
        }
        return img;
    }

    private static NativeImage solid(int w, int h, int abgr) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setPixelRGBA(x, y, abgr);
            }
        }
        return img;
    }

    private static NativeImage copy(NativeImage src) {
        NativeImage out = new NativeImage(src.getWidth(), src.getHeight(), false);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                out.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
            }
        }
        return out;
    }

    // ---- 1. 懒生成决策（无 provider 不生成 mask） ---------------------------

    @Test
    void noMaskWhenNoTintProviderEvenWithOutlines() {
        assertFalse(
                DynamicGunTextureCache.shouldBuildOutlineMask(
                        key(List.of(spec(1.0F, 0.0F, 0.0F, 2))),
                        List.of(PLUGIN)),
                "无 provider 时即使 gunOutlines 非空也必须跳过 mask 生成");
    }

    @Test
    void noMaskWhenNoOutlinesEvenWithProvider() {
        DynamicOutlineTintRegistry.register(PLUGIN, (stack, partialTick) -> new Vector4f(1, 0, 0, 1));
        assertFalse(
                DynamicGunTextureCache.shouldBuildOutlineMask(key(List.of()), List.of(PLUGIN)),
                "gunOutlines 为空时绝不生成 mask");
    }

    @Test
    void noMaskWhenProviderIsForUnrelatedPlugin() {
        DynamicOutlineTintRegistry.register(OTHER_PLUGIN, (stack, partialTick) -> new Vector4f(1, 0, 0, 1));
        assertFalse(
                DynamicGunTextureCache.shouldBuildOutlineMask(
                        key(List.of(spec(1.0F, 0.0F, 0.0F, 2))),
                        List.of(PLUGIN)),
                "仅当某描边插件自身有 provider 时才生成 mask");
    }

    // ---- 2. 有 provider 才生成 ---------------------------------------------

    @Test
    void maskWhenTintProviderPresent() {
        DynamicOutlineTintRegistry.register(PLUGIN, (stack, partialTick) -> new Vector4f(1, 0, 0, 1));
        assertTrue(
                DynamicGunTextureCache.shouldBuildOutlineMask(
                        key(List.of(spec(1.0F, 0.0F, 0.0F, 2))),
                        List.of(PLUGIN)),
                "描边插件有 provider 时必须生成 mask");
    }

    @Test
    void maskWhenAnyOutlinePluginHasProviderInInstallOrder() {
        DynamicOutlineTintRegistry.register(OTHER_PLUGIN, (stack, partialTick) -> new Vector4f(1, 0, 0, 1));
        assertTrue(
                DynamicGunTextureCache.shouldBuildOutlineMask(
                        key(List.of(spec(1.0F, 0.0F, 0.0F, 2))),
                        List.of(PLUGIN, OTHER_PLUGIN)),
                "任一个描边插件有 provider 即应生成 mask");
    }

    // ---- 3. 注册表存在性查询（不调用 provider） -------------------------------

    @Test
    void hasTintProviderReflectsRegistrationWithoutInvoking() {
        assertFalse(DynamicOutlineTintRegistry.hasTintProvider(List.of(PLUGIN)),
                "未注册时返回 false");
        DynamicOutlineTintRegistry.register(PLUGIN, (stack, partialTick) -> new Vector4f(1, 0, 0, 1));
        assertTrue(DynamicOutlineTintRegistry.hasTintProvider(List.of(PLUGIN)),
                "注册后返回 true");
        assertFalse(DynamicOutlineTintRegistry.hasTintProvider(List.of(ResourceLocation.parse("modularshoot:missing"))),
                "无关插件 id 返回 false");
        assertTrue(DynamicOutlineTintRegistry.hasTintProvider(List.of(PLUGIN, OTHER_PLUGIN)),
                "列表中含已注册 id 返回 true");
        assertFalse(DynamicOutlineTintRegistry.hasTintProvider(List.of()),
                "空列表不命中任何 provider，返回 false");
    }

    // ---- 4. 描边合成结果与旧算法一致 -----------------------------------------

    /** Old O(W·H·width²) neighbourhood rule, kept here as the reference. */
    private static boolean referenceHasAlphaNeighbor(int[] alpha, int w, int h, int x, int y, int width) {
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

    /** Reference full-gun outline painting matching the old applyGunOutlines. */
    private static NativeImage referenceApplyGunOutlines(NativeImage src, List<OutlineSpec> outlines) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = FastColor.ABGR32.alpha(src.getPixelRGBA(x, y));
            }
        }
        NativeImage out = copy(src);
        List<OutlineSpec> sorted = new ArrayList<>(outlines);
        sorted.sort(Comparator.comparingInt(OutlineSpec::width).reversed());
        for (OutlineSpec spec : sorted) {
            int width = Math.min(spec.width(), Math.max(w, h) - 1);
            if (width <= 0) {
                continue;
            }
            int color = FastColor.ABGR32.color(
                    Math.max(0, Math.min(255, Math.round(spec.alpha() * 255.0F))),
                    Math.max(0, Math.min(255, Math.round(spec.color().z() * 255.0F))),
                    Math.max(0, Math.min(255, Math.round(spec.color().y() * 255.0F))),
                    Math.max(0, Math.min(255, Math.round(spec.color().x() * 255.0F))));
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (alpha[y * w + x] == 0
                            && referenceHasAlphaNeighbor(alpha, w, h, x, y, width)) {
                        out.setPixelRGBA(x, y, color);
                    }
                }
            }
        }
        return out;
    }

    /** Reference mask build matching the old buildOutlineMask. */
    private static NativeImage referenceBuildOutlineMask(NativeImage src, List<OutlineSpec> outlines) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] alpha = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                alpha[y * w + x] = FastColor.ABGR32.alpha(src.getPixelRGBA(x, y));
            }
        }
        NativeImage mask = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask.setPixelRGBA(x, y, 0);
            }
        }
        List<OutlineSpec> sorted = new ArrayList<>(outlines);
        sorted.sort(Comparator.comparingInt(OutlineSpec::width).reversed());
        int white = FastColor.ABGR32.color(255, 255, 255, 255);
        for (OutlineSpec spec : sorted) {
            int width = Math.min(spec.width(), Math.max(w, h) - 1);
            if (width <= 0) {
                continue;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (alpha[y * w + x] == 0
                            && referenceHasAlphaNeighbor(alpha, w, h, x, y, width)) {
                        mask.setPixelRGBA(x, y, white);
                    }
                }
            }
        }
        return mask;
    }

    private static void assertImagesEqual(NativeImage actual, NativeImage expected, String what) {
        assertEquals(expected.getWidth(), actual.getWidth(), what + " width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + " height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getPixelRGBA(x, y), actual.getPixelRGBA(x, y),
                        what + " pixel at (" + x + "," + y + ")");
            }
        }
    }

    private static boolean hasAnyAlphaChange(NativeImage actual, NativeImage original) {
        for (int y = 0; y < actual.getHeight(); y++) {
            for (int x = 0; x < actual.getWidth(); x++) {
                if (actual.getPixelRGBA(x, y) != original.getPixelRGBA(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void applyGunOutlinesMatchesOldAlgorithmOnKeyPixels() {
        List<OutlineSpec> specs = List.of(spec(1.0F, 0.0F, 0.0F, 3), spec(1.0F, 1.0F, 0.0F, 1));
        try (NativeImage canvas = lShapeCanvas(16, 16);
             NativeImage expected = referenceApplyGunOutlines(canvas, specs)) {
            assertTrue(hasAnyAlphaChange(expected, canvas), "reference produced a stroke");
            CompositeTextureBuilder.applyGunOutlines(canvas, specs);
            assertImagesEqual(canvas, expected, "applyGunOutlines vs old algorithm");
        }
    }

    @Test
    void buildOutlineMaskMatchesOldAlgorithmOnKeyPixels() {
        List<OutlineSpec> specs = List.of(
                spec(1.0F, 0.0F, 0.0F, 3),
                spec(1.0F, 0.0F, 1.0F, 1),
                spec(0.0F, 1.0F, 0.0F, 2));
        try (NativeImage source = lShapeCanvas(16, 16);
             NativeImage expected = referenceBuildOutlineMask(source, specs)) {
            NativeImage mask = CompositeTextureBuilder.buildOutlineMask(source, specs);
            try (mask) {
                assertNotNull(mask, "mask must exist for non-empty outlines");
                assertImagesEqual(mask, expected, "buildOutlineMask vs old algorithm");
            }
        }
    }
}
