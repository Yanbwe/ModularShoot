package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the uninstall preflight gate (审查修复 M7) covering the three
 * most error-prone paths of the 0.1.4 uninstall overflow pre-check
 * (设计规格 §adds_slots 槽位扩展 §9 规划的卸载/匹配测试):
 *
 * <ol>
 *   <li>non-force uninstall that would overflow a slot type is rejected
 *       with {@code WOULD_OVERFLOW};</li>
 *   <li>{@code force} bypasses the pre-check entirely (both the
 *       {@code locked} flag and the overflow check);</li>
 *   <li>random uninstall filters the candidates (non-force excludes
 *       plugins whose removal would overflow).</li>
 * </ol>
 *
 * <p>The gate logic lives in the two package-visible pure functions
 * {@link PluginUninstallService#preflightReason} and
 * {@link PluginUninstallService#isRandomCandidate} (A/B below), which are
 * exercised with plain booleans. The overflow verdict itself is covered
 * against a stub {@link RegistryAccess} (C below): {@link MappedRegistry}
 * instances for {@code modularshoot:guns} and {@code modularshoot:plugins}
 * wrapped in an {@link RegistryAccess.ImmutableRegistryAccess}, same pattern
 * as {@code CrossReferenceValidatorTest}, with the hand-built definition
 * helpers of {@code EffectiveSlotServiceTest}.</p>
 */
class PluginUninstallServiceGateTest {

    // ---- Environment bootstrap (probe-verified recipe, identical to
    // BulletSnapshotBuilderTest) ------------------------------------------

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

    private static final ResourceLocation COMBAT = ResourceLocation.parse("modularshoot:combat");

    // ---- definition/instance builders (same shapes as
    // EffectiveSlotServiceTest) -------------------------------------------

    /** Builds a {@link GunDefinition} carrying only the given slot configuration. */
    private static GunDefinition gun(Map<ResourceLocation, Integer> slots) {
        return new GunDefinition(
                Optional.empty(),
                ResourceLocation.parse("m:tex"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                slots,
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Optional.empty());
    }

    /** Builds a {@link PluginDefinition} carrying only the given adds_slots. */
    private static PluginDefinition def(Map<ResourceLocation, Integer> addsSlots) {
        return new PluginDefinition(
                List.of(),
                0,
                ResourceLocation.parse("m:icon"),
                TextureScaleMode.AUTO,
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                addsSlots,
                Map.of(),
                Optional.empty());
    }

    /** Builds an installed plugin instance for the given plugin/type ids. */
    private static PluginInstance inst(ResourceLocation pluginId, ResourceLocation typeId) {
        return new PluginInstance(pluginId, UUID.randomUUID(), typeId, false);
    }

    // ---- A. preflightReason (设计规格 §6.1 检查规则) -----------------------

    @Test
    void nonForceOverflowRejected() {
        assertEquals(UninstallResult.Reason.WOULD_OVERFLOW,
                PluginUninstallService.preflightReason(false, false, true),
                "non-force removal that would overflow is rejected with WOULD_OVERFLOW");
    }

    @Test
    void lockedTakesPriorityOverOverflow() {
        assertEquals(UninstallResult.Reason.LOCKED,
                PluginUninstallService.preflightReason(false, true, true),
                "a locked plugin is reported LOCKED even when it would also overflow");
    }

    @Test
    void forceBypassesAll() {
        assertNull(PluginUninstallService.preflightReason(true, true, true),
                "force bypasses both the locked flag and the overflow check");
        assertNull(PluginUninstallService.preflightReason(true, false, true),
                "force bypasses the overflow check alone");
    }

    @Test
    void noBlockersPasses() {
        assertNull(PluginUninstallService.preflightReason(false, false, false),
                "an unlocked, non-overflowing removal is allowed");
    }

    // ---- B. isRandomCandidate (设计规格 §6.3 各卸载入口的行为) -------------

    @Test
    void forceAcceptsLockedAndOverflowing() {
        assertTrue(PluginUninstallService.isRandomCandidate(true, true, true),
                "force keeps locked and overflowing plugins as candidates");
    }

    @Test
    void nonForceRejectsLocked() {
        assertFalse(PluginUninstallService.isRandomCandidate(false, true, false),
                "non-force random uninstall skips locked plugins");
    }

    @Test
    void nonForceRejectsOverflowCandidate() {
        assertFalse(PluginUninstallService.isRandomCandidate(false, false, true),
                "non-force random uninstall excludes plugins whose removal would overflow");
    }

    @Test
    void unblockedCandidateAccepted() {
        assertTrue(PluginUninstallService.isRandomCandidate(false, false, false),
                "an unlocked, non-overflowing plugin stays a candidate");
    }

    // ---- C. removalCausesOverflow (stub RegistryAccess 集成) -------------

    /**
     * The overflow pre-check verdict against stub registries.
     *
     * <p>数学推演：枪械基础容量 {@code combat: 1}；插件 A 为唯一加槽插件
     * （{@code adds_slots {combat: 2}}），插件 B、C 无 {@code adds_slots}。
     * 装 A+B+C 后有效容量 = 1 + 2 = 3、已装数 3 → 不超编。移除 A 后容量回到
     * 1（A 的贡献消失）、剩余 B+C 已装数 2 &gt; 1 → 超编（{@code true}）。
     * 移除 B 后 A 仍在，容量 3、已装数 2 → 不超编（{@code false}）。</p>
     */
    @Test
    void removalCausesOverflowWhenSlotWouldExceedCapacity() {
        ResourceLocation gunId = ResourceLocation.parse("modularshoot:test_gate_gun");
        ResourceLocation adderId = ResourceLocation.parse("modularshoot:test_gate_adder");
        ResourceLocation plainBId = ResourceLocation.parse("modularshoot:test_gate_plain_b");
        ResourceLocation plainCId = ResourceLocation.parse("modularshoot:test_gate_plain_c");

        MappedRegistry<GunDefinition> guns = new MappedRegistry<>(
                ModularShootRegistries.GUNS_KEY, Lifecycle.stable());
        Registry.register(guns, gunId, gun(Map.of(COMBAT, 1)));
        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, adderId, def(Map.of(COMBAT, 2)));
        Registry.register(plugins, plainBId, def(Map.of()));
        Registry.register(plugins, plainCId, def(Map.of()));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(guns, plugins));

        PluginInstance adder = inst(adderId, COMBAT);
        PluginInstance plainB = inst(plainBId, COMBAT);
        PluginInstance plainC = inst(plainCId, COMBAT);
        GunData gunData = new GunData(gunId, UUID.randomUUID(),
                List.of(adder, plainB, plainC), 0, new CompoundTag());

        assertTrue(EffectiveSlotService.removalCausesOverflow(gunData, adder, access),
                "removing the only slot-adding plugin leaves 2 plugins in a capacity-1 slot");
        assertFalse(EffectiveSlotService.removalCausesOverflow(gunData, plainB, access),
                "removing a plain plugin keeps the adder's contribution, no overflow");
    }

    /**
     * 保守放行语义（见 {@code removalCausesOverflow} javadoc）：枪械定义无法
     * 从 stub 注册表解析时容量不可证明，一律不阻塞卸载。
     */
    @Test
    void missingGunDefinitionNeverBlocks() {
        ResourceLocation missingGunId = ResourceLocation.parse("modularshoot:test_gate_missing_gun");
        ResourceLocation plainDId = ResourceLocation.parse("modularshoot:test_gate_plain_d");

        MappedRegistry<PluginDefinition> plugins = new MappedRegistry<>(
                ModularShootRegistries.PLUGINS_KEY, Lifecycle.stable());
        Registry.register(plugins, plainDId, def(Map.of(COMBAT, 1)));
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(
                List.of(plugins));

        PluginInstance plainD = inst(plainDId, COMBAT);
        GunData gunData = new GunData(missingGunId, UUID.randomUUID(),
                List.of(plainD), 0, new CompoundTag());

        assertFalse(EffectiveSlotService.removalCausesOverflow(gunData, plainD, access),
                "an unresolvable gun definition never blocks removal");
    }
}
