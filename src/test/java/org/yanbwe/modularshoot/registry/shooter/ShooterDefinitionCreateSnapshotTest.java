package org.yanbwe.modularshoot.registry.shooter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless pure-function tests for {@link ShooterDefinition#buildSnapshot}
 * and the {@code createSnapshot(null, …)} degradation path.
 *
 * <p>No bootstrap is needed: the tested code paths touch only plain records
 * ({@link BulletStyle}, {@link DamageType}), plain maps and
 * {@link RegistryAccess#EMPTY} — {@code createSnapshot} with a {@code null}
 * source returns early in {@code readBound} before any registry lookup.</p>
 */
class ShooterDefinitionCreateSnapshotTest {

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("m", path);
    }

    private static Map<ResourceLocation, Double> templateStats() {
        Map<ResourceLocation, Double> stats = new HashMap<>();
        stats.put(id("a"), 1.0);
        stats.put(id("b"), 2.0);
        return stats;
    }

    private static Map<ResourceLocation, Boolean> templateTraits() {
        Map<ResourceLocation, Boolean> traits = new HashMap<>();
        traits.put(id("ignite"), true);
        return traits;
    }

    private static Function<ResourceLocation, Optional<Double>> reader(
            Map<ResourceLocation, Double> values) {
        return bind -> Optional.ofNullable(values.get(bind));
    }

    private static Function<ResourceLocation, Optional<Double>> emptyReader() {
        return bind -> Optional.empty();
    }

    // ---- buildSnapshot bind-merge semantics ------------------------------

    @Test
    void mergeBindsKeepsTemplateWhenReaderEmpty() {
        BulletSnapshot snapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), null,
                List.of(id("c")), emptyReader(), null);
        assertEquals(1.0, snapshot.getStat(id("a")), 1e-9);
        assertEquals(2.0, snapshot.getStat(id("b")), 1e-9);
        // reader 空 → 保留模板值，c 不进入快照
        assertFalse(snapshot.getStats().containsKey(id("c")));
    }

    @Test
    void mergeBindsOverridesOnPresent() {
        BulletSnapshot snapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), null,
                List.of(id("a")), reader(Map.of(id("a"), 9.0)), null);
        assertEquals(9.0, snapshot.getStat(id("a")), 1e-9);
        assertEquals(2.0, snapshot.getStat(id("b")), 1e-9);
    }

    @Test
    void mergeBindsIntroducesNewKey() {
        BulletSnapshot snapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), null,
                List.of(id("x")), reader(Map.of(id("x"), 5.0)), null);
        // bind 引入模板未声明的新键也允许（与 extra_values 口径一致）
        assertEquals(5.0, snapshot.getStat(id("x")), 1e-9);
        assertEquals(1.0, snapshot.getStat(id("a")), 1e-9);
        assertEquals(2.0, snapshot.getStat(id("b")), 1e-9);
    }

    // ---- buildSnapshot snapshot fields -----------------------------------

    @Test
    void buildSnapshotProducesSnapshot() {
        BulletStyle style = new BulletStyle(Optional.empty(), List.of());
        BulletSnapshot snapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), style,
                List.of(id("a")), reader(Map.of(id("a"), 9.0)), null);
        // stats 合并正确（bind 覆盖模板值）
        assertEquals(9.0, snapshot.getStat(id("a")), 1e-9);
        assertEquals(2.0, snapshot.getStat(id("b")), 1e-9);
        // traits 原样进入
        assertTrue(snapshot.getTrait(id("ignite")));
        // style 以变体风格覆写通道挂载（独立发射视觉通道）
        assertSame(style, snapshot.getVariantStyleOverride());
        // 独立发射快照约定：gunId/gunInstanceUuid/shooter 恒 null
        assertNull(snapshot.getGunId());
        assertNull(snapshot.getGunInstanceUuid());
        assertNull(snapshot.getShooter());
        // damageType 传 null → 快照为 null（发射边界再补默认）
        assertNull(snapshot.getDamageType());
    }

    // ---- createSnapshot with null source ---------------------------------

    @Test
    void createSnapshotNullSourceKeepsTemplate() {
        ShooterDefinition def = new ShooterDefinition(
                templateStats(), templateTraits(), Optional.empty(), Optional.empty(),
                List.of(id("a")));
        BulletSnapshot snapshot = def.createSnapshot(null, RegistryAccess.EMPTY);
        // source null → readBound 恒 empty → 不触任何注册表（EMPTY 安全），模板原样保留
        assertEquals(1.0, snapshot.getStat(id("a")), 1e-9);
        assertEquals(2.0, snapshot.getStat(id("b")), 1e-9);
        assertTrue(snapshot.getTrait(id("ignite")));
        assertNull(snapshot.getDamageType());
        assertNull(snapshot.getGunId());
        assertNull(snapshot.getGunInstanceUuid());
        assertNull(snapshot.getShooter());
    }

    // ---- damage type passthrough -----------------------------------------

    @Test
    void buildSnapshotDamageTypePassedThrough() {
        Holder<DamageType> holder = Holder.direct(new DamageType("modularshoot.test", 0.1f));
        BulletSnapshot snapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), null,
                List.of(), emptyReader(), holder);
        assertSame(holder, snapshot.getDamageType());
        // null 分支：不传 damageType → 快照为 null
        BulletSnapshot nullSnapshot = ShooterDefinition.buildSnapshot(
                templateStats(), templateTraits(), null,
                List.of(), emptyReader(), null);
        assertNull(nullSnapshot.getDamageType());
    }
}
