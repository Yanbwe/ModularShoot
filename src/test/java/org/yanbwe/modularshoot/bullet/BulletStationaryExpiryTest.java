package org.yanbwe.modularshoot.bullet;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the stationary-bullet expiry decision
 * {@link BulletTickHandler#isStationary} (审查修复 M2).
 *
 * <p>A bullet whose frozen {@code bullet_speed} is ≤ 0 can never advance past
 * its range ({@code stepLength = bullet_speed / 20} stays zero), so
 * {@code checkRangeExpiry} can never fire and the bullet would linger in the
 * {@link BulletManager} forever. The fix decides "stationary" from the
 * snapshot alone — no {@code Level} required.</p>
 *
 * <p>Headless: only {@link BulletSnapshotBuilder} + {@link BulletSnapshot} are
 * touched, so the same vanilla bootstrap recipe as
 * {@link BulletSnapshotBuilderTest} applies.</p>
 */
class BulletStationaryExpiryTest {

    // ---- Environment bootstrap (same recipe as BulletSnapshotBuilderTest) --

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

    private static final java.util.UUID SHOOTER = java.util.UUID.randomUUID();

    private static BulletSnapshot snapshotWithSpeed(double speed) {
        return new BulletSnapshotBuilder()
                .stat(ModularShootAttributes.BULLET_SPEED.getKey().location(), speed)
                .build();
    }

    @Test
    void zeroSpeedIsStationary() {
        // speed = 0.0（显式存在 BULLET_SPEED 键）：stepLength 恒为 0，
        // 无法推进，必须立即过期。
        assertTrue(BulletTickHandler.isStationary(snapshotWithSpeed(0.0)),
                "speed exactly 0.0 (explicit key) is stationary");
    }

    @Test
    void negativeSpeedIsStationary() {
        // speed < 0（插件修饰符可把值压到下限 0 以下）：同样无法推进。
        assertTrue(BulletTickHandler.isStationary(snapshotWithSpeed(-1.0)),
                "negative speed is stationary");
    }

    @Test
    void tinyPositiveSpeedIsNotStationary() {
        // 极小的正速度仍能（缓慢地）推进并最终越过射程，不应立即过期。
        assertFalse(BulletTickHandler.isStationary(snapshotWithSpeed(0.001)),
                "tiny but positive speed still advances and is not stationary");
    }

    @Test
    void normalSpeedIsNotStationary() {
        // 正常速度自然推进，由 checkRangeExpiry 负责生命周期结束。
        assertFalse(BulletTickHandler.isStationary(snapshotWithSpeed(1.0)),
                "normal positive speed is not stationary");
    }

    @Test
    void missingSpeedStatDefaultsToStationary() {
        // stats 缺键时 getStat 返回 0.0 → 视为静止（与 advancePosition 读到的
        // 值一致：stepLength = 0，永不越程）。
        assertTrue(BulletTickHandler.isStationary(new BulletSnapshotBuilder().build()),
                "absent bullet_speed stat degrades to 0.0 and is stationary");
    }
}
