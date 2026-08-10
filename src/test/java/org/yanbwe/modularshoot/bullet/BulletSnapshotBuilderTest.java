package org.yanbwe.modularshoot.bullet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageType;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BulletSnapshotBuilder} independent-firing snapshot
 * construction (设计文档 §独立发射).
 *
 * <p>Pure headless logic — no {@code Level} required. Constructing a bare
 * {@link DamageType} record touches {@code SoundEvents}' static initializer,
 * so the non-null holder path needs the same vanilla bootstrap the
 * {@code ModularShootAPIItemBindingTest} probe verified (FML shim +
 * {@code Bootstrap.bootStrap()} + {@code GameData.unfreezeData()}, see the
 * probe verdicts recorded there). The registry-backed fallback overload is
 * exercised against {@link RegistryAccess#EMPTY} (which contains no
 * {@code DAMAGE_TYPE} registry, so the fallback patch path throws — the same
 * degradation contract the {@code ModularShootAPI#fireBullet} facade relies
 * on at runtime).</p>
 */
class BulletSnapshotBuilderTest {

    // ---- Environment bootstrap (probe-verified, same recipe as
    // ModularShootAPIItemBindingTest) --------------------------------------

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

    private static final ResourceLocation DAMAGE = ResourceLocation.parse("test:damage");
    private static final ResourceLocation SPEED = ResourceLocation.parse("test:speed");
    private static final ResourceLocation IGNITE = ResourceLocation.parse("test:ignite");
    private static final ResourceLocation CHARGES = ResourceLocation.parse("test:charges");

    @Test
    void chainStatTraitStateAccumulate() {
        // 链式累积：两个 stat、一个 trait、一个 state → build() 后逐字段断言。
        BulletSnapshot snapshot = new BulletSnapshotBuilder()
                .stat(DAMAGE, 12.5)
                .stat(SPEED, 3.0)
                .trait(IGNITE, true)
                .state(CHARGES, 2)
                .build();
        assertEquals(12.5, snapshot.getStat(DAMAGE), 1.0E-9, "first stat accumulates");
        assertEquals(3.0, snapshot.getStat(SPEED), 1.0E-9, "second stat accumulates");
        assertTrue(snapshot.getTrait(IGNITE), "trait flag accumulates");
        assertEquals(Integer.valueOf(2), snapshot.getState(CHARGES), "state value accumulates");
        // 独立发射约定：gunId/gunInstanceUuid/shooter 一律为 null。
        assertNull(snapshot.getGunId(), "independent firing carries no gun id");
        assertNull(snapshot.getGunInstanceUuid(), "independent firing carries no gun instance uuid");
        assertNull(snapshot.getShooter(), "builder never sets a shooter");
    }

    @Test
    void styleWritesVariantOverrideChannel() {
        // style() 写入"独立发射视觉通道"（variantStyleOverride），compose 时
        // 优先于回退外观。
        BulletStyle style = new BulletStyle(Optional.empty(), List.of());
        BulletSnapshot snapshot = new BulletSnapshotBuilder().style(style).build();
        assertSame(style, snapshot.getVariantStyleOverride(),
                "style object is carried through to the snapshot verbatim");
    }

    @Test
    void noStyleLeavesOverrideNull() {
        assertNull(new BulletSnapshotBuilder().build().getVariantStyleOverride(),
                "unset style leaves the visual override null");
    }

    @Test
    void damageTypePassedThrough() {
        // 无头环境经类级 Bootstrap 后可用 Holder.direct + 裸 DamageType 记录
        // 合成一个假 holder；传入后必须原样带到快照（非 null holder 路径）。
        Holder<DamageType> holder = Holder.direct(
                new DamageType("test", DamageScaling.NEVER, 0.0f));
        BulletSnapshot snapshot = new BulletSnapshotBuilder().damageType(holder).build();
        assertSame(holder, snapshot.getDamageType(), "damage type holder passes through verbatim");
    }

    @Test
    void buildCopiesDefensively() {
        // build() 后继续给 builder 加 stat，不影响已构建快照
        // （builder 的 map 可复用，防御拷贝由 BulletSnapshot 构造器保证）。
        BulletSnapshotBuilder builder = new BulletSnapshotBuilder().stat(DAMAGE, 5.0);
        BulletSnapshot snapshot = builder.build();
        builder.stat(DAMAGE, 99.0);
        assertEquals(5.0, snapshot.getStat(DAMAGE), 1.0E-9,
                "post-build builder mutation must not leak into the built snapshot");
    }

    @Test
    void buildAllowsNullDamageType() {
        // damageType 未设置时 build() 必须成功，快照携带 null
        // （由调用方在发射时通过 build(RegistryAccess) 或 fireBullet 补丁补齐）。
        BulletSnapshot snapshot = new BulletSnapshotBuilder().stat(DAMAGE, 1.0).build();
        assertNull(snapshot.getDamageType(), "unset damage type stays null");
    }

    @Test
    void buildWithEmptyRegistryThrowsForMissingDamageType() {
        // build(RegistryAccess) 的缺省补丁路径：EMPTY 视图不含 DAMAGE_TYPE
        // 注册表，holderOrThrow 必须抛出 —— 与 fireBullet 门面在运行时
        // 世界视图下成功补齐是同一条代码路径。
        BulletSnapshotBuilder builder = new BulletSnapshotBuilder().stat(DAMAGE, 1.0);
        assertThrows(IllegalStateException.class,
                () -> builder.build(RegistryAccess.EMPTY),
                "missing damage type + registry without DAMAGE_TYPE registry must throw");
    }
}
