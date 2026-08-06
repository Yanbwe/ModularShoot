package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link BulletSnapshot} — deep-copy independence,
 * shooter/gun identity carry-over, {@code multiplyStat} semantics and the
 * server-side-only {@code variantStyleOverride} field. No {@code RegistryAccess}
 * is required: the {@code damageType} argument is passed as {@code null}
 * (design 文档 §子弹快照).
 */
class BulletSnapshotTest {

    private static final ResourceLocation HIT_DAMAGE = rl("modularshoot", "hit_damage");
    private static final ResourceLocation FIRE_RATE = rl("modularshoot", "fire_rate");
    private static final ResourceLocation TRAIT_A = rl("modularshoot", "trait_a");
    private static final ResourceLocation TRAIT_B = rl("modularshoot", "trait_b");
    private static final ResourceLocation STATE_X = rl("modularshoot", "state_x");
    private static final ResourceLocation STATE_Y = rl("modularshoot", "state_y");
    private static final ResourceLocation ABSENT_STAT = rl("modularshoot", "absent_stat");

    private static ResourceLocation rl(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    /** Builds a {@link BulletSnapshot} with the given stats/traits/state and identity. */
    private static BulletSnapshot snap(
            Map<ResourceLocation, Double> stats,
            Map<ResourceLocation, Boolean> traits,
            Map<ResourceLocation, Object> state,
            UUID shooter,
            ResourceLocation gunId,
            UUID gunInstanceUuid) {
        return new BulletSnapshot(stats, traits, null, shooter, gunId, gunInstanceUuid, state);
    }

    // ------------------------------------------------------------------
    // copy() 深拷贝独立性
    // ------------------------------------------------------------------

    @Test
    void copyIsDeepIndependent() {
        BulletSnapshot original = snap(
                new HashMap<>(Map.of(HIT_DAMAGE, 10.0, FIRE_RATE, 5.0)),
                new HashMap<>(Map.of(TRAIT_A, true, TRAIT_B, false)),
                new HashMap<>(Map.of(STATE_X, 1, STATE_Y, 2)),
                null, null, null);
        BulletSnapshot copy = original.copy();

        // 修改副本：原快照各值不变
        copy.setStat(HIT_DAMAGE, 99.0);
        copy.setStat(FIRE_RATE, -5.0);
        copy.setTrait(TRAIT_A, false);
        copy.setTrait(TRAIT_B, true);
        copy.setState(STATE_X, 999);
        copy.setState(STATE_Y, "mutated");
        assertEquals(10.0, original.getStat(HIT_DAMAGE), 1e-9);
        assertEquals(5.0, original.getStat(FIRE_RATE), 1e-9);
        assertTrue(original.getTrait(TRAIT_A));
        assertFalse(original.getTrait(TRAIT_B));
        assertEquals(1, (Integer) original.getState(STATE_X));
        assertEquals(2, (Integer) original.getState(STATE_Y));

        // 反向验证：修改原快照，副本不变
        original.setStat(HIT_DAMAGE, -1.0);
        original.setTrait(TRAIT_A, false);
        original.setState(STATE_X, -7);
        assertEquals(99.0, copy.getStat(HIT_DAMAGE), 1e-9);
        assertEquals(-5.0, copy.getStat(FIRE_RATE), 1e-9);
        assertFalse(copy.getTrait(TRAIT_A));
        assertTrue(copy.getTrait(TRAIT_B));
        assertEquals(999, (Integer) copy.getState(STATE_X));
        assertEquals("mutated", copy.getState(STATE_Y));
    }

    // ------------------------------------------------------------------
    // copy() 携带射手与枪械身份
    // ------------------------------------------------------------------

    @Test
    void copyCarriesShooterAndGunIdentity() {
        UUID shooter = UUID.randomUUID();
        UUID gunInstanceUuid = UUID.randomUUID();
        BulletSnapshot original = snap(
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                shooter, rl("modularshoot", "test_gun"), gunInstanceUuid);
        BulletSnapshot copy = original.copy();
        assertEquals(shooter, copy.getShooter());
        assertEquals(rl("modularshoot", "test_gun"), copy.getGunId());
        assertEquals(gunInstanceUuid, copy.getGunInstanceUuid());
    }

    // ------------------------------------------------------------------
    // multiplyStat 乘算语义（缺失键按 0.0 计算）
    // ------------------------------------------------------------------

    @Test
    void multiplyStatMultipliesExistingAndAbsent() {
        BulletSnapshot snap = snap(
                new HashMap<>(Map.of(HIT_DAMAGE, 10.0)),
                new HashMap<>(),
                new HashMap<>(),
                null, null, null);
        snap.multiplyStat(HIT_DAMAGE, 2.0);
        assertEquals(20.0, snap.getStat(HIT_DAMAGE), 1e-9, "existing key 10.0 × 2.0 → 20.0");
        snap.multiplyStat(ABSENT_STAT, 2.0);
        assertEquals(0.0, snap.getStat(ABSENT_STAT), 1e-9,
                "absent key multiplies getOrDefault(id, 0.0) → 0.0");
    }

    // ------------------------------------------------------------------
    // variantStyleOverride 设置与 copy 携带
    // ------------------------------------------------------------------

    @Test
    void variantStyleOverrideSetAndCopy() {
        BulletStyle style = new BulletStyle(Optional.empty(), List.of());
        BulletSnapshot snap = snap(new HashMap<>(), new HashMap<>(), new HashMap<>(),
                null, null, null);
        assertNull(snap.getVariantStyleOverride(), "未设置时为 null");
        snap.setVariantStyleOverride(style);
        assertSame(style, snap.getVariantStyleOverride(), "设置后读取相同实例");
        BulletSnapshot copy = snap.copy();
        assertSame(style, copy.getVariantStyleOverride(), "copy() 携带 variantStyleOverride");
        // 原快照清除后，副本仍持有
        snap.setVariantStyleOverride(null);
        assertNull(snap.getVariantStyleOverride());
        assertSame(style, copy.getVariantStyleOverride());
    }
}
