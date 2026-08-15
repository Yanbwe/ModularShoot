package org.yanbwe.modularshoot.bullet;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.trait.RemoveReason;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless unit tests for the BulletManager per-chunk spatial index
 * (阶段 2 / 任务 2.1).
 *
 * <p>Verifies that {@code BulletManager} maintains a {@code ChunkPos ->
 * Set<BulletRecord>} index so that range queries return only nearby bullets
 * instead of scanning every active bullet:
 * <ul>
 *   <li>range query returns only nearby bullets (and excludes far ones);</li>
 *   <li>a bullet that moves across a chunk boundary is re-indexed — the old
 *       area no longer returns it and the new area does;</li>
 *   <li>a removed bullet is removed from the index and no longer returned.</li>
 * </ul>
 *
 * <p>{@link BulletRecord} is constructible in pure unit tests (plain
 * {@code BulletSnapshot}, {@link Vec3}, {@link ComposedBulletStyle#DEFAULT}),
 * and the spatial index itself needs no {@code Level} — it operates purely on
 * world positions and {@link net.minecraft.world.level.ChunkPos}. The
 * {@code BulletManager} constructor is package-private and takes only a
 * dimension {@link ResourceKey}, so a manager can be built headlessly.</p>
 */
class BulletSpatialIndexTest {

    // ---- Environment bootstrap (same recipe as BulletStationaryExpiryTest) --

    static {
        // FML shim: FeatureFlags.<clinit> -> FeatureFlagLoader needs a
        // non-null LoadingModList; of() installs an empty instance.
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), Map.of());
        // DataFixers.<clinit> requires a current game version.
        net.minecraft.SharedConstants.setVersion(net.minecraft.DetectedVersion.BUILT_IN);
        // Full vanilla registry bootstrap (also freezes vanilla registries via
        // the NeoForge vanillaSnapshot patch).
        net.minecraft.server.Bootstrap.bootStrap();
        // Reopen the registries for test-side registration.
        net.neoforged.neoforge.registries.GameData.unfreezeData();
    }

    private static final ResourceKey<Level> DIM_KEY =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("test:dim"));

    private BulletManager manager;

    @BeforeEach
    void setUp() {
        TraitHookRegistry.clear();
        manager = new BulletManager(DIM_KEY);
    }

    /** Builds a minimal bullet with the given id and world position. */
    private static BulletRecord bullet(int id, Vec3 pos) {
        return new BulletRecord(
                new BulletSnapshot(
                        new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>()),
                null, pos, new Vec3(1, 0, 0), id, ComposedBulletStyle.DEFAULT);
    }

    // ------------------------------------------------------------------
    // 范围查询：只返回附近子弹
    // ------------------------------------------------------------------

    @Test
    void rangeQueryReturnsOnlyNearbyBullets() {
        // near: (x=10, z=10) -> chunk (0, 0); far: (x=1000, z=10) -> chunk (62, 0)
        BulletRecord near = bullet(1, new Vec3(10, 0, 10));
        BulletRecord far = bullet(2, new Vec3(1000, 0, 10));
        manager.addBullet(near);
        manager.addBullet(far);

        Collection<BulletRecord> result =
                manager.getActiveBulletsInRange(new Vec3(0, 0, 0), 100.0);

        assertTrue(result.contains(near),
                "range query must include the bullet within radius");
        assertFalse(result.contains(far),
                "range query must exclude the bullet outside radius");
    }

    // ------------------------------------------------------------------
    // 跨 chunk 移动后索引更新
    // ------------------------------------------------------------------

    @Test
    void crossChunkMovementUpdatesIndex() {
        BulletRecord moving = bullet(1, new Vec3(0, 0, 0)); // chunk (0, 0)
        manager.addBullet(moving);

        assertTrue(manager.getActiveBulletsInRange(new Vec3(0, 0, 0), 50.0).contains(moving),
                "bullet starts indexed in its origin chunk");

        // Move across a chunk boundary: x=1000 -> chunk (62, 0).
        moving.setPosition(new Vec3(1000, 0, 0));

        assertFalse(manager.getActiveBulletsInRange(new Vec3(0, 0, 0), 50.0).contains(moving),
                "after cross-chunk move the old area must no longer return the bullet");
        assertTrue(manager.getActiveBulletsInRange(new Vec3(1000, 0, 0), 50.0).contains(moving),
                "after cross-chunk move the new area must return the bullet");
    }

    // ------------------------------------------------------------------
    // 删除后不再返回
    // ------------------------------------------------------------------

    @Test
    void removalRemovesBulletFromIndex() {
        BulletRecord doomed = bullet(1, new Vec3(0, 0, 0));
        manager.addBullet(doomed);
        assertTrue(manager.getActiveBulletsInRange(new Vec3(0, 0, 0), 50.0).contains(doomed));

        manager.removeBullet(1, RemoveReason.MANUAL);

        assertNull(manager.getBulletById(1), "bullet must be removed from the id index");
        assertFalse(manager.getActiveBulletsInRange(new Vec3(0, 0, 0), 50.0).contains(doomed),
                "removed bullet must no longer be returned by the range query");
    }

    // ------------------------------------------------------------------
    // 边界语义等价：getActiveBulletsInRange vs BulletSyncService.isInRenderDistance
    // (max(|dx|,|dz|) <= radius, 忽略 y, 恰好等于 radius 包含)
    // ------------------------------------------------------------------

    @Test
    void boundarySemanticsMatchRenderDistanceCulling() {
        double radius = 100.0;
        Vec3 center = new Vec3(0, 0, 0);

        BulletRecord exactlyAtRadius = bullet(1, new Vec3(radius, 0, 0));      // dx == radius -> include
        BulletRecord justInside = bullet(2, new Vec3(radius - 1.0e-4, 0, 0));   // dx < radius  -> include
        BulletRecord justOutside = bullet(3, new Vec3(radius + 1.0e-4, 0, 0));  // dx > radius  -> exclude
        BulletRecord highY = bullet(4, new Vec3(0, 1_000_000, 0));              // y 极大, dx/dz=0 -> include
        BulletRecord lowY = bullet(5, new Vec3(0, -1_000_000, 0));              // y 极小, dx/dz=0 -> include
        manager.addBullet(exactlyAtRadius);
        manager.addBullet(justInside);
        manager.addBullet(justOutside);
        manager.addBullet(highY);
        manager.addBullet(lowY);

        Collection<BulletRecord> result = manager.getActiveBulletsInRange(center, radius);

        assertTrue(result.contains(exactlyAtRadius),
                "Chebyshev boundary is inclusive: bullet exactly at radius must be included");
        assertTrue(result.contains(justInside),
                "bullet just inside the radius must be included");
        assertFalse(result.contains(justOutside),
                "bullet just outside the radius must be excluded");
        assertTrue(result.contains(highY),
                "the vertical axis must be ignored: a bullet far above must be included");
        assertTrue(result.contains(lowY),
                "the vertical axis must be ignored: a bullet far below must be included");
        // Sanity: the metric is Chebyshev max(|dx|,|dz|), so a bullet off the
        // horizontal diagonal at exactly the radius on both axes is included too.
        BulletRecord diagonalCorner = bullet(6, new Vec3(radius / Math.sqrt(2), 0, radius / Math.sqrt(2)));
        manager.addBullet(diagonalCorner);
        assertTrue(manager.getActiveBulletsInRange(center, radius).contains(diagonalCorner),
                "Chebyshev distance must be max(|dx|,|dz|), not Euclidean");
    }

    // ------------------------------------------------------------------
    // 索引一致性不变式：byId 中每颗子弹在索引里且仅在一个桶
    // ------------------------------------------------------------------

    @Test
    void eachActiveBulletAppearsInExactlyOneBucket() {
        List<BulletRecord> bullets = List.of(
                bullet(1, new Vec3(0, 0, 0)),        // chunk (0, 0)
                bullet(2, new Vec3(1000, 0, 0)),     // chunk (62, 0)
                bullet(3, new Vec3(0, 0, 1000)),     // chunk (0, 62)
                bullet(4, new Vec3(500, 0, -300)));  // chunk (31, -19)
        for (BulletRecord b : bullets) {
            manager.addBullet(b);
        }

        for (BulletRecord b : manager.getAllBullets()) {
            long expectedKey = ChunkPos.asLong(
                    SectionPos.blockToSectionCoord(b.getPosition().x),
                    SectionPos.blockToSectionCoord(b.getPosition().z));
            int appearances = 0;
            for (Set<BulletRecord> bucket : manager.getSpatialIndex().values()) {
                if (bucket.contains(b)) {
                    appearances++;
                }
            }
            assertEquals(1, appearances,
                    "each live bullet must appear in exactly one chunk bucket (id=" + b.getBulletId() + ")");
            assertTrue(manager.getSpatialIndex().get(expectedKey).contains(b),
                    "bullet (id=" + b.getBulletId() + ") must be in the bucket of its current chunk");
        }
    }

    @Test
    void crossChunkMoveUpdatesBucketInvariant() {
        BulletRecord moving = bullet(1, new Vec3(0, 0, 0)); // chunk (0, 0)
        manager.addBullet(moving);
        long oldKey = ChunkPos.asLong(0, 0);
        assertTrue(manager.getSpatialIndex().get(oldKey).contains(moving),
                "bullet must start in its origin chunk bucket");

        // Move across a chunk boundary: x=1000 -> chunk (62, 0).
        moving.setPosition(new Vec3(1000, 0, 0));
        long newKey = ChunkPos.asLong(62, 0);

        Set<BulletRecord> oldBucket = manager.getSpatialIndex().get(oldKey);
        assertFalse(oldBucket != null && oldBucket.contains(moving),
                "after cross-chunk move the old bucket must no longer contain the bullet");
        assertTrue(manager.getSpatialIndex().get(newKey).contains(moving),
                "after cross-chunk move the new bucket must contain the bullet");
    }
}
