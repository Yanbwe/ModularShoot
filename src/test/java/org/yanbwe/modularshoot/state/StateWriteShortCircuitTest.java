package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for 阶段 3 / 任务 3.1 — GunState/PlayerState 读写短路与缓存.
 *
 * <p>These tests assert the hot-path wins:
 * <ul>
 *   <li><b>只读视图不重复分配</b> — {@link GunState#of(CompoundTag, RegistryAccess)}
 *       read-only view must hand back the <em>same</em> synthesised
 *       {@link GunData} instance on every access instead of allocating a new
 *       record per getter.</li>
 *   <li><b>同值不深拷贝</b> — {@link GunStateStorage#setStateValue} must not
 *       {@code copy()} the whole state table when the new value is identical
 *       to the stored one; it should return the same tag instance.</li>
 *   <li><b>PlayerState 同值不写回</b> — the extractable write gate
 *       {@link PlayerState#writeIfChanged} (the core of
 *       {@code PlayerState.setTypedValue}) must short-circuit on
 *       {@link java.util.Objects#equals} and <em>not</em> invoke the
 *       {@code setData} writer when the value is unchanged, only persisting a
 *       new payload when the value genuinely changes. This is exercised
 *       through the package-private seam with a recording writer because the
 *       real {@code setData}/{@link net.minecraft.world.entity.player.Player}
 *       would require the full vanilla-bootstrap entity harness (3.1 审查
 *       Medium 3 — 最小可测 seam).</li>
 * </ul>
 * </p>
 *
 * <p>The unused multi-key batch API ({@code GunStateStorage.setStateValues})
 * was removed as dead code (no production multi-key write path; 3.1 审查
 * Medium 1), so there is deliberately no batch-copy-count test here.</p>
 */
class StateWriteShortCircuitTest {

    private static final ResourceLocation INT_STATE =
            ResourceLocation.parse("modularshoot:test_heat");

    /** A registry access that actually registers the state used by these tests. */
    private static final RegistryAccess REGISTRY = buildStateRegistry();

    private static RegistryAccess buildStateRegistry() {
        Registry<StateDefinition> states = new MappedRegistry<>(
                ModularShootRegistries.STATES_KEY, Lifecycle.stable());
        register(states, INT_STATE, StateValueType.INT);
        return new RegistryAccess.ImmutableRegistryAccess(List.of(states));
    }

    private static void register(
            Registry<StateDefinition> states, ResourceLocation id, StateValueType type) {
        StateDefinition def = new StateDefinition(
                StateDomain.GUN, type, type.zeroValue(),
                StateDisplay.of("test", Optional.of("#FFFFFF")), List.of());
        Registry.register(states, id, def);
    }

    /**
     * Builds a state tag already holding {@code INT_STATE -> 5}.
     *
     * @return a {@link CompoundTag} with one int entry written
     */
    private static CompoundTag tagWithInt5() {
        return GunStateStorage.setStateValue(
                new CompoundTag(), INT_STATE, 5, REGISTRY);
    }

    // ------------------------------------------------------------------
    // 1. GunState read-only view caches the synthesised GunData
    // ------------------------------------------------------------------

    @Test
    void readOnlyGunDataIsCachedAcrossReads() {
        // Read-only view needs no ItemStack and no registry for currentGunData();
        // RegistryAccess.EMPTY is fine because no lookups happen here.
        CompoundTag state = tagWithInt5();
        GunState view = GunState.of(state, REGISTRY);

        GunData first = view.currentGunData();
        GunData second = view.currentGunData();
        GunData third = view.currentGunData();

        assertSame(first, second,
                "只读视图每次 currentGunData() 必须返回同一缓存实例，避免每 getter 分配");
        assertSame(second, third,
                "只读视图后续 currentGunData() 仍须命中同一缓存实例");
    }

    // ------------------------------------------------------------------
    // 2. GunStateStorage.setStateValue skips the whole-table deep copy
    //    when the value is unchanged
    // ------------------------------------------------------------------

    @Test
    void setStateValueUnchangedReturnsSameTagWithoutCopy() {
        CompoundTag current = tagWithInt5();

        // Writing the identical value must NOT deep-copy the whole state table.
        CompoundTag rewritten = GunStateStorage.setStateValue(current, INT_STATE, 5, REGISTRY);

        assertSame(current, rewritten,
                "同值写回应短路并返回同一 tag 实例（不做整表深拷贝）");
        assertEquals(5, GunStateStorage.getStateValue(current, INT_STATE, REGISTRY),
                "未重写前原值应保持不变");
    }

    @Test
    void setStateValueChangedStillCopiesAndUpdates() {
        CompoundTag current = tagWithInt5();

        CompoundTag changed = GunStateStorage.setStateValue(current, INT_STATE, 9, REGISTRY);

        // A genuine change must still produce a new (immutable) tag.
        assertNotSame(current, changed, "值确实变化时必须返回新 tag");
        assertEquals(9, GunStateStorage.getStateValue(changed, INT_STATE, REGISTRY),
                "新 tag 应携带更新后的值");
        // The original tag must be untouched (pure function).
        assertEquals(5, GunStateStorage.getStateValue(current, INT_STATE, REGISTRY),
                "源 tag 不得被原地修改");
    }

    // ------------------------------------------------------------------
    // 3. PlayerState.setTypedValue short-circuit gate
    //    (PlayerState.writeIfChanged seam with a recording writer)
    // ------------------------------------------------------------------

    @Test
    void writeIfChangedSameValueDoesNotInvokeWriter() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        AtomicInteger writes = new AtomicInteger();

        PlayerState.writeIfChanged(data, INT_STATE, StateValueType.INT, REGISTRY, 5,
                newData -> writes.incrementAndGet());

        assertEquals(0, writes.get(),
                "同值写应短路：writer（即 setData）不得被调用");
        assertEquals(5, data.getStateValue(INT_STATE, REGISTRY),
                "原数据保持不变");
    }

    @Test
    void writeIfChangedChangedValueInvokesWriterExactlyOnce() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        AtomicInteger writes = new AtomicInteger();
        PlayerStateData[] installed = new PlayerStateData[1];

        PlayerState.writeIfChanged(data, INT_STATE, StateValueType.INT, REGISTRY, 7,
                newData -> {
                    writes.incrementAndGet();
                    installed[0] = newData;
                });

        assertEquals(1, writes.get(),
                "值确实变化时必须恰好调用一次 writer（对应一次 setData）");
        assertEquals(7, installed[0].getStateValue(INT_STATE, REGISTRY),
                "writer 收到的应是携带新值的新 payload");
        assertEquals(5, data.getStateValue(INT_STATE, REGISTRY),
                "原 payload 不得被修改（不可变）");
    }

    @Test
    void writeIfChangedAbsentKeyDefaultValueDoesNotPersistExplicitEntry() {
        // 行为变化（3.1 审查 Low）：向缺失键写默认值（INT 的 0）在短路后不再
        // 落显式条目——读取时缺失键本身解出默认值 0，Objects.equals 命中短路。
        PlayerStateData data = new PlayerStateData(new CompoundTag());
        AtomicInteger writes = new AtomicInteger();

        PlayerState.writeIfChanged(data, INT_STATE, StateValueType.INT, REGISTRY, 0,
                newData -> writes.incrementAndGet());

        assertEquals(0, writes.get(),
                "缺失键写默认值也应短路（读取已返回默认值）");
        assertEquals(0, data.getStateValue(INT_STATE, REGISTRY),
                "读取缺失键仍返回默认值 0");
        assertFalse(data.stateTag().contains(INT_STATE.toString()),
                "同值短路不应为缺失键落显式条目");
        assertTrue(data.stateTag().isEmpty(),
                "空 payload 在写默认值短路后仍应为空");
    }

    // ------------------------------------------------------------------
    // 4. PlayerStateData write path reuses the backing tag when unchanged
    // ------------------------------------------------------------------

    @Test
    void playerStateDataSameValueReusesBackingTag() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());

        PlayerStateData rewritten =
                data.withStateValue(INT_STATE, 5, REGISTRY);

        assertSame(data.stateTag(), rewritten.stateTag(),
                "PlayerState 同值写回应复用同一 backing tag（不深拷贝/不产生新数据）");
        assertEquals(5, data.getStateValue(INT_STATE, REGISTRY),
                "原值应保持不变");
    }

    @Test
    void playerStateDataChangedValueProducesNewBackingTag() {
        PlayerStateData data = new PlayerStateData(tagWithInt5());

        PlayerStateData changed = data.withStateValue(INT_STATE, 7, REGISTRY);

        assertNotSame(data.stateTag(), changed.stateTag(),
                "值变化时必须产生新 backing tag");
        assertEquals(7, changed.getStateValue(INT_STATE, REGISTRY),
                "新数据应携带更新后的值");
        assertEquals(5, data.getStateValue(INT_STATE, REGISTRY),
                "原数据不得被修改（不可变）");
    }

    // ------------------------------------------------------------------
    // 5. PlayerState.clearState no-op short-circuit (阶段 7 / 任务 7.1)
    // ------------------------------------------------------------------

    @Test
    void shouldSkipClearWhenKeyAbsent() {
        // 键不存在时读取已返回默认值 0，清除是 no-op → 跳过 setData/markDirty。
        PlayerStateData data = new PlayerStateData(new CompoundTag());
        assertTrue(PlayerState.shouldSkipClear(data, INT_STATE, REGISTRY),
                "缺失键的清除是 no-op（读取已得默认值）");
    }

    @Test
    void shouldSkipClearWhenValueIsDefault() {
        // 显式存储了默认值 0：清除后读取仍为 0 → no-op，跳过写回。
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        PlayerStateData withDefault = data.withStateValue(INT_STATE, 0, REGISTRY);
        assertTrue(PlayerState.shouldSkipClear(withDefault, INT_STATE, REGISTRY),
                "显式存储默认值时清除是 no-op");
    }

    @Test
    void shouldNotSkipClearWhenValueIsNonDefault() {
        // 存储了非默认值 5：清除会真正改变下次读取 → 必须走 setData/markDirty。
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        assertFalse(PlayerState.shouldSkipClear(data, INT_STATE, REGISTRY),
                "非默认值时清除必须真正执行（写回逻辑）");
    }

    @Test
    void clearStateValueFromNonDefaultRemovesEntry() {
        // 非默认值场景下，清除应产生移除了该键的新 payload。
        PlayerStateData data = new PlayerStateData(tagWithInt5());
        PlayerStateData cleared = data.clearStateValue(INT_STATE);
        assertEquals(0, cleared.getStateValue(INT_STATE, REGISTRY),
                "清除后读取恢复默认值 0");
        assertFalse(cleared.stateTag().contains(INT_STATE.toString()),
                "清除后 backing tag 不再含该键");
        assertEquals(5, data.getStateValue(INT_STATE, REGISTRY),
                "原 payload 不得被修改（不可变）");
    }
}
