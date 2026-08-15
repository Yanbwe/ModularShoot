package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for 阶段 3 / 任务 3.3 — PlayerState 同步节流.
 *
 * <p>These tests assert the throttling contract for per-player state sync,
 * mirroring {@code GunSyncThrottleManager}:
 * <ul>
 *   <li><b>同值不触发同步</b> — a same-value write short-circuits (阶段 3 /
 *       任务 3.1 {@link PlayerState#writeIfChanged}) so {@code setData} &mdash;
 *       and therefore the dirty mark chained into it &mdash; never runs; the
 *       throttle manager stays clean and no sync is ever due.</li>
 *   <li><b>真实变化在窗口内合并为一次同步</b> — multiple genuine changes within
 *       one throttle window (same tick) collapse into exactly one
 *       {@code shouldSync == true}, i.e. one attachment sync at the window
 *       boundary, instead of one per {@code setData}.</li>
 *   <li><b>节流到期后同步</b> — a dirty marker is held back until
 *       {@link PlayerStateThrottleManager#THROTTLE_INTERVAL_TICKS} ticks have
 *       elapsed since the last sync, then allowed to flush; after
 *       {@code markSynced} the next window restarts.</li>
 * </ul>
 * </p>
 *
 * <p>The write-path seam ({@code PlayerState.writeIfChanged} + a recording
 * writer) is used because the real {@code setData}/{@link
 * net.minecraft.world.entity.player.Player} would require the full
 * vanilla-bootstrap entity harness, matching the approach in {@code
 * StateWriteShortCircuitTest}.</p>
 */
class PlayerStateThrottleTest {

    private static final ResourceLocation INT_STATE =
            ResourceLocation.parse("modularshoot:test_heat");

    private static final UUID PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_PLAYER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** A registry access that actually registers the state used by these tests. */
    private static final RegistryAccess REGISTRY = buildStateRegistry();

    private static RegistryAccess buildStateRegistry() {
        Registry<StateDefinition> states = new MappedRegistry<>(
                ModularShootRegistries.STATES_KEY, Lifecycle.stable());
        StateDefinition def = new StateDefinition(
                StateDomain.PLAYER, StateValueType.INT, StateValueType.INT.zeroValue(),
                StateDisplay.of("test", Optional.of("#FFFFFF")), List.of());
        Registry.register(states, INT_STATE, def);
        return new RegistryAccess.ImmutableRegistryAccess(List.of(states));
    }

    /** Clears the singleton throttle map so tests are isolated from each other. */
    @BeforeEach
    void clearThrottleState() {
        PlayerStateThrottleManager.getInstance().cleanup(Set.of());
    }

    /** Builds a state tag already holding {@code INT_STATE -> 5}. */
    private static CompoundTag tagWithInt5() {
        return GunStateStorage.setStateValue(new CompoundTag(), INT_STATE, 5, REGISTRY);
    }

    // ------------------------------------------------------------------
    // 1. 同值不触发同步
    // ------------------------------------------------------------------

    @Test
    void sameValueWriteDoesNotMarkDirtyAndNeverSyncs() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        AtomicInteger writes = new AtomicInteger();

        // Production writer chain: only a genuine change reaches setData, and only
        // setData (a real write) would then mark the player dirty (schedule a sync).
        // Writing the identical value must short-circuit before both.
        PlayerState.writeIfChanged(data, INT_STATE, StateValueType.INT, REGISTRY, 5,
                newData -> {
                    writes.incrementAndGet();
                    PlayerStateThrottleManager.getInstance().markDirty(PLAYER_UUID);
                });

        assertEquals(0, writes.get(),
                "同值写应短路：writer（即 setData → markDirty 链路）不得被调用");
        assertFalse(PlayerStateThrottleManager.getInstance().shouldSync(PLAYER_UUID, 100),
                "同值写不应产生 dirty 标记，因此任何 tick 都不会触发同步");
    }

    @Test
    void changedValueWriteMarksDirty() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());

        // A genuine change reaches the writer, which in production both installs
        // the new payload and marks the player dirty.
        PlayerState.writeIfChanged(data, INT_STATE, StateValueType.INT, REGISTRY, 7,
                newData -> PlayerStateThrottleManager.getInstance().markDirty(PLAYER_UUID));

        // Dirty is recorded (held back by the throttle window at tick 0).
        assertFalse(PlayerStateThrottleManager.getInstance().shouldSync(PLAYER_UUID, 0),
                "刚标记 dirty 时仍在节流窗口内，不应立即同步");
    }

    // ------------------------------------------------------------------
    // 2. 真实变化在窗口内合并为一次同步
    // ------------------------------------------------------------------

    @Test
    void multipleChangesWithinWindowFlushExactlyOnce() {
        PlayerStateThrottleManager manager = PlayerStateThrottleManager.getInstance();

        // Two genuine changes in the same tick (e.g. two state writes in one tick).
        manager.markDirty(PLAYER_UUID);
        manager.markDirty(PLAYER_UUID);

        int syncsDue = 0;
        for (long tick = 0; tick <= PlayerStateThrottleManager.THROTTLE_INTERVAL_TICKS; tick++) {
            if (manager.shouldSync(PLAYER_UUID, tick)) {
                syncsDue++;
            }
        }
        // window = [0, interval): dirty at the first change only allows one sync
        assertEquals(1, syncsDue,
                "窗口内多次变化只允许在窗口边界触发一次同步");
    }

    // ------------------------------------------------------------------
    // 3. 节流到期后同步
    // ------------------------------------------------------------------

    @Test
    void dirtyStateIsHeldThenFlushedAfterInterval() {
        PlayerStateThrottleManager manager = PlayerStateThrottleManager.getInstance();
        int interval = PlayerStateThrottleManager.THROTTLE_INTERVAL_TICKS;

        manager.markDirty(PLAYER_UUID);

        // Held back within the window.
        for (long tick = 0; tick < interval; tick++) {
            assertFalse(manager.shouldSync(PLAYER_UUID, tick),
                    "tick=" + tick + " 仍在节流窗口内，不应同步");
        }
        // Window expired -> one sync now due.
        assertTrue(manager.shouldSync(PLAYER_UUID, interval),
                "节流窗口到期后应允许同步");

        // After marking synced the dirty flag is cleared for the same tick.
        manager.markSynced(PLAYER_UUID, interval);
        assertFalse(manager.shouldSync(PLAYER_UUID, interval),
                "markSynced 后同 tick 不应再次同步");
    }

    @Test
    void markSyncedRestartsNextWindowForLaterChanges() {
        PlayerStateThrottleManager manager = PlayerStateThrottleManager.getInstance();
        int interval = PlayerStateThrottleManager.THROTTLE_INTERVAL_TICKS;

        manager.markDirty(PLAYER_UUID);
        manager.markSynced(PLAYER_UUID, interval);

        // A change in the tick right after a sync is held until the next boundary.
        manager.markDirty(PLAYER_UUID);
        assertFalse(manager.shouldSync(PLAYER_UUID, interval + 1),
                "刚同步后立刻的新变化应被下一个窗口吞掉");
        assertTrue(manager.shouldSync(PLAYER_UUID, interval + interval),
                "下一个窗口边界应再次允许同步");
    }

    @Test
    void throttleStateIsPerPlayerNotShared() {
        PlayerStateThrottleManager manager = PlayerStateThrottleManager.getInstance();

        manager.markDirty(PLAYER_UUID);

        // A different player's state must not be affected.
        assertFalse(manager.shouldSync(OTHER_PLAYER_UUID, 100),
                "节流状态按 player 隔离，另一个 player 不应被标记为 dirty");
        assertTrue(manager.shouldSync(PLAYER_UUID, PlayerStateThrottleManager.THROTTLE_INTERVAL_TICKS),
                "dirty 的 player 在窗口到期后应同步");
    }
}
