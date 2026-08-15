package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for 阶段 4 / 任务 4.4 渲染热路径对象复用:
 *
 * <ul>
 *   <li>{@link RenderInterpolation} 提供写入式插值 API — 把结果写入调用方复用的
 *       JOML {@link Vector3d}，而不是每次 {@code Vec3.lerp} 返回新对象。</li>
 *   <li>{@link Model3DRenderer} 复用 static {@link RandomSource}（固定种子）而非
 *       每次渲染 {@code create()} 新建。</li>
 *   <li>{@link Model3DRenderer#MODEL_CACHE} 是有界 LRU，超过上限时逐出最久未
 *       访问条目而非无限增长。</li>
 * </ul>
 *
 * <p>与既有渲染测试一致：{@link Model3DRenderer} 类加载会初始化
 * {@code RenderType} 等客户端状态，因此静态块先完成 vanilla registry
 * bootstrap（项目惯例见 {@code BulletRenderSortTest}）。</p>
 */
class RenderAllocationTest {

    private static final float EPS = 1e-5F;

    static {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    // ---- 1. Write-into interpolation API ---------------------------------

    @Test
    void lerpPositionWritesIntoTheCallersReusedOutputObject() {
        Vector3d out = new Vector3d(0, 0, 0);
        Vec3 prev = new Vec3(0, 0, 0);
        Vec3 current = new Vec3(10, 0, 0);

        Vector3d result = RenderInterpolation.lerpPosition(prev, current, 0.5f, out);

        assertSame(out, result, "写入式 API 必须复用调用方传入的输出对象，而不是返回新 Vec3");
        assertEquals(5.0, result.x, EPS);
        assertEquals(0.0, result.y, EPS);
        assertEquals(0.0, result.z, EPS);
    }

    @Test
    void lerpPositionWritesAllThreeComponents() {
        Vector3d out = new Vector3d(-1, -1, -1);
        Vec3 prev = new Vec3(1, 2, 3);
        Vec3 current = new Vec3(3, 6, 9);

        RenderInterpolation.lerpPosition(prev, current, 0.25f, out);

        assertEquals(1.5, out.x, EPS);
        assertEquals(3.0, out.y, EPS);
        assertEquals(4.5, out.z, EPS);
    }

    @Test
    void lerpPositionMatchesTheExistingReturningOverload() {
        Vec3 prev = new Vec3(1, 2, 3);
        Vec3 current = new Vec3(4, 5, 6);
        float t = 0.37f;

        Vector3d written = new Vector3d();
        RenderInterpolation.lerpPosition(prev, current, t, written);
        Vec3 returned = RenderInterpolation.lerpPosition(prev, current, t);

        assertEquals(returned.x, written.x, EPS);
        assertEquals(returned.y, written.y, EPS);
        assertEquals(returned.z, written.z, EPS);
    }

    // ---- 2. Shared RandomSource reuse ------------------------------------

    @Test
    void model3dRendererReusesOneSharedRandomSource() {
        RandomSource first = Model3DRenderer.sharedRandomSource();
        RandomSource second = Model3DRenderer.sharedRandomSource();

        assertSame(first, second,
                "渲染路径必须复用同一个 static RandomSource，而不是每次渲染 create() 新建");
    }

    @Test
    void sharedRandomSourceIsDeterministicForFixedSeed() {
        RandomSource rs = Model3DRenderer.sharedRandomSource();

        rs.setSeed(42L);
        int first = rs.nextInt();
        rs.setSeed(42L);
        int second = rs.nextInt();

        assertEquals(first, second,
                "固定种子（渲染用 42L）下必须产生相同随机值，保证逐次渲染结果一致");
    }

    // ---- 3. Bounded LRU model cache --------------------------------------

    @Test
    void modelCacheIsBoundedAndNeverExceedsItsLruCap() {
        int cap = Model3DRenderer.modelCacheMaxEntries();

        // Insert more distinct model locations than the cache allows. The cache
        // must stay at its LRU cap rather than grow without bound.
        for (int i = 0; i < cap + 10; i++) {
            Model3DRenderer.MODEL_CACHE.put(loc("model_" + i), new FakeBakedModel());
        }

        assertEquals(cap, Model3DRenderer.MODEL_CACHE.size(),
                "模型缓存必须受 LRU 上限约束，不能无限增长");
    }

    @Test
    void modelCacheEvictsLeastRecentlyUsedEntry() {
        Model3DRenderer.MODEL_CACHE.clear();
        int cap = Model3DRenderer.modelCacheMaxEntries();

        // Fill to the cap with distinct model locations.
        for (int i = 0; i < cap; i++) {
            Model3DRenderer.MODEL_CACHE.put(loc("k" + i), new FakeBakedModel());
        }
        // Touch k0 so it is no longer the LRU — k1 is now the least-recently-used.
        Model3DRenderer.MODEL_CACHE.get(loc("k0"));

        // Inserting one more key must evict the LRU (k1), not the touched k0.
        Model3DRenderer.MODEL_CACHE.put(loc("new"), new FakeBakedModel());

        assertEquals(cap, Model3DRenderer.MODEL_CACHE.size(),
                "插入新条目后缓存仍保持在上限");
        assertEquals(null, Model3DRenderer.MODEL_CACHE.get(loc("k1")),
                "超过上限时逐出最久未访问的条目（k1 被 k0 的命中挤到队尾）");
        assertNotNull(Model3DRenderer.MODEL_CACHE.get(loc("k0")),
                "被命中的条目刷新了新鲜度，不应被逐出");
        assertNotNull(Model3DRenderer.MODEL_CACHE.get(loc("new")),
                "新插入的条目保留在缓存中");
    }

    private static ResourceLocation loc(String path) {
        return ResourceLocation.fromNamespaceAndPath("modularshoot_test", path);
    }

    /** Minimal {@link BakedModel} stub for cache-policy tests (never drawn). */
    private static final class FakeBakedModel implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return false;
        }

        @Override
        public boolean usesBlockLight() {
            return false;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return null;
        }

        @Override
        public ItemTransforms getTransforms() {
            return null;
        }

        @Override
        public ItemOverrides getOverrides() {
            return null;
        }
    }
}
