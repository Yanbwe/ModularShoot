package org.yanbwe.modularshoot.shooting;

import java.util.HashMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link ShootEffectRegistry} (机制三 效果贡献者,
 * 规格 §5): registration-order execution, pellet-context pass-through and the
 * empty-registry no-op. No {@code RegistryAccess} is required: the
 * {@code damageType} argument is passed as {@code null} and the per-bullet
 * state map as an empty {@link HashMap} (设计文档 §子弹快照).
 *
 * <p>The registry is a static singleton shared across tests, so
 * {@link #clearRegistry()} resets it before every case (same pattern as
 * {@code DynamicOutlineTintRegistryTest}). Effects ignore the {@code player} /
 * {@code gun} parameters here ({@code null} is fine).</p>
 */
class ShootEffectRegistryTest {

    /** Per-bullet state key used to record execution-order markers. */
    private static final ResourceLocation MARKER = ResourceLocation.parse("modularshoot:marker");

    /** Builds an empty snapshot with {@code null} damage type and identity. */
    private static BulletSnapshot snapshot() {
        return new BulletSnapshot(
                new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>());
    }

    @BeforeEach
    void clearRegistry() {
        ShootEffectRegistry.clear();
    }

    // ------------------------------------------------------------------
    // 按注册顺序执行
    // ------------------------------------------------------------------

    @Test
    void effectsRunInRegistrationOrder() {
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) ->
                appendMarker(snapshot, "A"));
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) ->
                appendMarker(snapshot, "B"));

        BulletSnapshot snapshot = snapshot();
        ShootEffectRegistry.applyEffects(null, null, snapshot, 0, 1);

        assertEquals("AB", snapshot.getState(MARKER),
                "effects execute in registration order and later effects see earlier mutations");
    }

    // ------------------------------------------------------------------
    // pelletIndex / totalPellets 透传
    // ------------------------------------------------------------------

    @Test
    void applyEffectsPassesPelletContext() {
        int[] captured = {-1, -1};
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) -> {
            captured[0] = pelletIndex;
            captured[1] = totalPellets;
        });

        ShootEffectRegistry.applyEffects(null, null, snapshot(), 1, 5);

        assertEquals(1, captured[0], "pelletIndex is passed through (0-based)");
        assertEquals(5, captured[1], "totalPellets is passed through");
    }

    // ------------------------------------------------------------------
    // 空注册表 no-op
    // ------------------------------------------------------------------

    @Test
    void noEffectsIsNoOp() {
        assertDoesNotThrow(() -> ShootEffectRegistry.applyEffects(null, null, snapshot(), 0, 1),
                "an empty registry must not throw");
    }

    // ------------------------------------------------------------------
    // 第三方回调异常隔离：坏 effect 被记录并跳过，其余 effect 照常执行
    // ------------------------------------------------------------------

    @Test
    void throwingEffectIsSkippedAndLaterEffectsStillRun() {
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) -> {
            throw new IllegalStateException("deliberate test failure");
        });
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) ->
                appendMarker(snapshot, "A"));

        BulletSnapshot snapshot = snapshot();
        assertDoesNotThrow(() -> ShootEffectRegistry.applyEffects(null, null, snapshot, 0, 1),
                "a throwing effect must not abort the per-pellet effect pipeline");
        assertEquals("A", snapshot.getState(MARKER),
                "the throwing effect is logged and skipped; the normal effect still runs");
    }

    @Test
    void throwingEffectBetweenNormalEffectsStillRunsTheRest() {
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) ->
                appendMarker(snapshot, "A"));
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) -> {
            throw new IllegalStateException("deliberate test failure");
        });
        ShootEffectRegistry.register((player, gun, snapshot, pelletIndex, totalPellets) ->
                appendMarker(snapshot, "B"));

        BulletSnapshot snapshot = snapshot();
        ShootEffectRegistry.applyEffects(null, null, snapshot, 0, 1);

        assertEquals("AB", snapshot.getState(MARKER),
                "effects registered before and after the throwing one all still run");
    }

    /** Appends {@code mark} to the per-bullet state {@link #MARKER} chain. */
    private static void appendMarker(BulletSnapshot snapshot, String mark) {
        String current = snapshot.getState(MARKER);
        snapshot.setState(MARKER, (current == null ? "" : current) + mark);
    }
}
