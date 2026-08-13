package org.yanbwe.modularshoot.client.render;

import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the back-to-front bullet render ordering (透明遮挡修复).
 *
 * <p>The billboard RenderType no longer writes depth (the square sprite's
 * fully-transparent corners must not occlude bullets behind it), so depth
 * testing can no longer resolve which bullet draws in front of another —
 * alpha blending does, and blending order is submission order. Bullets must
 * therefore be drawn <em>farthest first</em>, so a nearer bullet's opaque
 * sphere correctly overwrites (alpha = 1) the farther bullet behind it while
 * its transparent corners let the farther bullet show through.</p>
 *
 * <p>The sort key is the bullet's raw tick position
 * ({@link BulletRenderObject#getPosition()}) — the interpolated position is
 * not available until draw time and differs from the raw position by less
 * than one sync segment, a distance far smaller than the visible overlap
 * scale the ordering is meant to resolve.</p>
 */
class BulletRenderSortTest {

    static {
        // 类初始化需要 MC 客户端类（RenderLevelStageEvent.Stage），而它依赖
        // 原版注册表 bootstrap。项目惯例（同 DatapackJsonCodecTest）：
        // FML shim + 版本 shim + 完整 vanilla registry bootstrap，使本测试
        // 在任何运行方式（全量/过滤单跑）下都稳定。
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static BulletRenderObject bullet(int id, double x, double y, double z) {
        return new BulletRenderObject(id, new Vec3(x, y, z), new Vec3(1, 0, 0),
                null, null, BulletRenderObject.RENDER_MODE_BILLBOARD, 1.0F, null, List.of());
    }

    @Test
    void emptyCollectionYieldsEmptyList() {
        assertTrue(BulletRenderDispatcher.sortBackToFront(List.of(), Vec3.ZERO).isEmpty(),
                "no bullets, no draw order");
    }

    @Test
    void singleBulletIsUnchanged() {
        BulletRenderObject b = bullet(1, 10, 0, 0);
        assertEquals(List.of(b), BulletRenderDispatcher.sortBackToFront(List.of(b), Vec3.ZERO),
                "a lone bullet has nothing to order against");
    }

    @Test
    void sortsFarthestFirst() {
        BulletRenderObject near = bullet(1, 1, 0, 0);
        BulletRenderObject far = bullet(2, 50, 0, 0);
        BulletRenderObject mid = bullet(3, 10, 0, 0);
        assertEquals(List.of(far, mid, near),
                BulletRenderDispatcher.sortBackToFront(List.of(near, mid, far), Vec3.ZERO),
                "draw order must be descending camera distance so nearer bullets blend on top");
    }

    @Test
    void keepsInputOrderForEqualDistances() {
        BulletRenderObject a = bullet(1, 5, 0, 0);
        BulletRenderObject b = bullet(2, -5, 0, 0);
        BulletRenderObject c = bullet(3, 0, 5, 0);
        List<BulletRenderObject> input = List.of(a, b, c);
        assertEquals(input, BulletRenderDispatcher.sortBackToFront(input, Vec3.ZERO),
                "a stable sort keeps the manager's insertion order for ties, so the result is deterministic");
    }

    @Test
    void distancesUseSquaredDistanceFromCamera() {
        BulletRenderObject near = bullet(1, 2, 3, 4);
        BulletRenderObject far = bullet(2, -20, 15, 30);
        Vec3 camera = new Vec3(1, 1, 1);
        assertEquals(List.of(far, near),
                BulletRenderDispatcher.sortBackToFront(List.of(near, far), camera),
                "ordering key is distance² from the camera position, not from world origin");
    }
}
