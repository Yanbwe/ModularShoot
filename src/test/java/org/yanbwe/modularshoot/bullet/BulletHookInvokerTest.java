package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BulletHookInvoker} exception isolation: a
 * third-party {@link TraitHookType#ON_TICK} hook that throws is logged and
 * skipped, leaving the remaining hooks and the bullet pipeline unaffected
 * (设计文档 §特性运行时钩子, D3 第三方回调异常隔离).
 *
 * <p>{@link BulletRecord} is constructible in pure unit tests: its
 * dependencies are plain data — a {@link BulletSnapshot} (built with
 * {@code null} damage type/identity like {@code BulletSnapshotTest}),
 * {@link Vec3} vectors (pure math, no environment) and the frozen
 * {@link ComposedBulletStyle#DEFAULT} fallback composition. No
 * {@code RegistryAccess}, level or entity is required.</p>
 *
 * <p>{@link TraitHookRegistry} is a static singleton shared across tests,
 * so {@link #clearRegistry()} resets it before every case (same pattern as
 * {@code ShootEffectRegistry.clear()}).</p>
 */
class BulletHookInvokerTest {

    private static final ResourceLocation THROWING_HOOK = ResourceLocation.parse("modularshoot:test_throwing_hook");
    private static final ResourceLocation NORMAL_HOOK = ResourceLocation.parse("modularshoot:test_normal_hook");

    /** Builds a minimal bullet: empty snapshot, zero position, +X direction. */
    private static BulletRecord bullet() {
        return new BulletRecord(
                new BulletSnapshot(
                        new HashMap<>(), new HashMap<>(), null, null, null, null, new HashMap<>()),
                null, Vec3.ZERO, new Vec3(1, 0, 0), 1, ComposedBulletStyle.DEFAULT);
    }

    @BeforeEach
    void clearRegistry() {
        TraitHookRegistry.clear();
    }

    // ------------------------------------------------------------------
    // ON_TICK 异常隔离：坏钩子被记录并跳过，其余钩子照常执行
    // ------------------------------------------------------------------

    @Test
    void throwingOnTickHookIsSkippedAndLaterHooksStillRun() {
        AtomicInteger ticks = new AtomicInteger();
        TraitCallbacks.TraitTickCallback throwing = (bullet, snapshot) -> {
            throw new IllegalStateException("deliberate test failure");
        };
        TraitHookRegistry.register(THROWING_HOOK, TraitHookType.ON_TICK, throwing);
        TraitHookRegistry.register(NORMAL_HOOK, TraitHookType.ON_TICK,
                (TraitCallbacks.TraitTickCallback) (bullet, snapshot) -> ticks.incrementAndGet());

        assertDoesNotThrow(() -> BulletHookInvoker.fireOnTick(bullet()),
                "a throwing onTick hook must not abort the bullet pipeline");
        assertEquals(1, ticks.get(),
                "the throwing hook is logged and skipped; the normal hook still runs");
    }

    @Test
    void throwingOnTickHookRegisteredLastStillDoesNotBreakEarlierHooks() {
        AtomicInteger ticks = new AtomicInteger();
        TraitHookRegistry.register(NORMAL_HOOK, TraitHookType.ON_TICK,
                (TraitCallbacks.TraitTickCallback) (bullet, snapshot) -> ticks.incrementAndGet());
        TraitCallbacks.TraitTickCallback throwing = (bullet, snapshot) -> {
            throw new IllegalStateException("deliberate test failure");
        };
        TraitHookRegistry.register(THROWING_HOOK, TraitHookType.ON_TICK, throwing);

        BulletRecord bullet = bullet();
        assertDoesNotThrow(() -> BulletHookInvoker.fireOnTick(bullet),
                "an exception thrown by the last hook must not escape fireOnTick");
        assertEquals(1, ticks.get(),
                "the normal hook still ran even though a later hook threw");
    }
}
