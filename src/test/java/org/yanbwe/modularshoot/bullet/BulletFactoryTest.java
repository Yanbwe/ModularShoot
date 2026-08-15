package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless unit tests for 阶段 6 / 任务 6.3 — BulletManager 职责拆分.
 *
 * <p>Verifies two architectural guarantees after the split:</p>
 * <ul>
 *   <li>{@link BulletFactory} owns bullet <em>creation</em>: it composes the
 *       visual style at creation, allocates a fresh per-dimension id from the
 *       manager, and registers the produced record. It performs no network
 *       marking (that is the caller / {@code CreationCoordinator}'s job).</li>
 *   <li>{@link BulletManager} is storage/retrieval only — it no longer exposes
 *       the old mixed {@code fireBullet} entry point that performed visual
 *       composition and network marking in-line.</li>
 * </ul>
 *
 * <p>Like {@link BulletSpatialIndexTest}, this is fully headless: {@link BulletRecord}
 * is constructed from a plain {@link BulletSnapshot}, {@link Vec3} and a stubbed
 * composer, and the spatial index needs no {@code Level}. The {@link BulletFactory}
 * constructor is package-private and accepts an injectable {@code Composer} so the
 * test can stub composition without a running registry.</p>
 */
class BulletFactoryTest {

    // ---- Environment bootstrap (same recipe as BulletSpatialIndexTest) ----

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

    /** A fixed composed style the stubbed composer always returns. */
    private static final ComposedBulletStyle TEST_STYLE = ComposedBulletStyle.DEFAULT;

    private BulletManager manager;

    @BeforeEach
    void setUp() {
        TraitHookRegistry.clear();
        manager = new BulletManager(DIM_KEY);
    }

    private static BulletSnapshot emptySnapshot() {
        return new BulletSnapshot(
                new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>());
    }

    /**
     * Builds a factory whose composer is stubbed to always return
     * {@link #TEST_STYLE}, so creation never touches the real registry.
     */
    private static BulletFactory stubbedFactory() {
        return new BulletFactory((ra, snapshot, gunData) -> TEST_STYLE);
    }

    // ------------------------------------------------------------------
    // BulletFactory composes + registers (creation logic correct)
    // ------------------------------------------------------------------

    @Test
    void factoryFreezesComposedStyleAtCreation() {
        BulletFactory factory = stubbedFactory();
        BulletSnapshot snapshot = emptySnapshot();
        Vec3 pos = new Vec3(1, 0, 1);
        // Non-normalized direction is supplied to verify the record normalises it.
        Vec3 direction = new Vec3(2, 0, 0);

        BulletRecord bullet = factory.createAndRegister(
                manager, null, RegistryAccess.EMPTY, pos, direction, snapshot, null, null);

        assertSame(TEST_STYLE, bullet.getComposedStyle(),
                "factory must freeze the composer's visual style at creation");
        assertSame(snapshot, bullet.getSnapshot(), "factory must carry the given snapshot");
        assertEquals(new Vec3(1, 0, 0), bullet.getDirection(),
                "factory-created bullet must normalise the supplied direction");
    }

    @Test
    void factoryRegistersProducedBulletInManager() {
        BulletFactory factory = stubbedFactory();
        BulletSnapshot snapshot = emptySnapshot();
        Vec3 pos = new Vec3(0, 0, 0);

        BulletRecord bullet = factory.createAndRegister(
                manager, null, RegistryAccess.EMPTY, pos, new Vec3(1, 0, 0), snapshot, null, null);

        assertSame(bullet, manager.getBulletById(bullet.getBulletId()),
                "factory must register the produced bullet so the manager can retrieve it");
        assertTrue(manager.getActiveBulletsInRange(pos, 50.0).contains(bullet),
                "the registered bullet must be in the manager's spatial index");
    }

    @Test
    void factoryAllocatesFreshIdPerCreation() {
        BulletFactory factory = stubbedFactory();
        BulletSnapshot snapshot = emptySnapshot();
        Vec3 pos = new Vec3(0, 0, 0);

        BulletRecord first = factory.createAndRegister(
                manager, null, RegistryAccess.EMPTY, pos, new Vec3(1, 0, 0), snapshot, null, null);
        BulletRecord second = factory.createAndRegister(
                manager, null, RegistryAccess.EMPTY, pos, new Vec3(1, 0, 0), snapshot, null, null);

        assertNotEquals(first.getBulletId(), second.getBulletId(),
                "each bullet must get a fresh unique per-dimension id");
        assertNotNull(manager.getBulletById(first.getBulletId()));
        assertNotNull(manager.getBulletById(second.getBulletId()));
    }

    // ------------------------------------------------------------------
    // BulletManager is storage/retrieval only
    // ------------------------------------------------------------------

    @Test
    void bulletManagerNoLongerExposesMixedFireBullet() {
        // The old BulletManager.fireBullet(Level, Vec3, Vec3, BulletSnapshot, UUID)
        // in-line visual composition and network marking. After the split the
        // manager must be storage-only: invoking creation must route through
        // BulletFactory, so a matching public fireBullet method must not exist.
        assertThrows(NoSuchMethodException.class,
                () -> BulletManager.class.getMethod(
                        "fireBullet",
                        Level.class, Vec3.class, Vec3.class, BulletSnapshot.class, UUID.class),
                "BulletManager must not expose the mixed fireBullet creation entry point");
    }
}
