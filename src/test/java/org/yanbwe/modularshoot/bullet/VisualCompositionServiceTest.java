package org.yanbwe.modularshoot.bullet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.registry.Trait;
import org.yanbwe.modularshoot.registry.gun.AttachLayerModifier;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.Modifier;
import org.yanbwe.modularshoot.registry.gun.ScaleModifier;
import org.yanbwe.modularshoot.registry.gun.TintModifier;
import org.yanbwe.modularshoot.state.StateDefinition;
import org.yanbwe.modularshoot.state.StateDomain;
import org.yanbwe.modularshoot.state.StateValueType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link VisualCompositionService}. The service
 * is exercised through a {@link FakeService} that stubs the four protected
 * lookup seams ({@link VisualCompositionService#lookupGun} / {@code lookupPlugin}
 * / {@code lookupTrait} / {@code allStateDefsWithVisualModifiers}); no
 * {@link RegistryAccess} is required.
 *
 * <p>Each case builds a {@link BulletSnapshot} via the {@link Snap} helper
 * and asserts the resulting {@link ComposedBulletStyle} fields. The
 * algorithm is exercised along: scale 连乘, tint 通道连乘, attach_layer
 * 全保留 + 顺序, base last-wins by priority, base 安装序后装赢, no-base
 * fallback, 全缺失 → DEFAULT, plugin 未注册 skip, NaN scale skip,
 * tint clamp / negative skip, state 条件 +/- 与未注册 state skip, and
 * 未知 modifier 类型跳过 (设计规格 §4.2 / §5).</p>
 */
class VisualCompositionServiceTest {

    // ------------------------------------------------------------------
    // Fake service: stubs the four lookup seams via in-memory maps.
    // ------------------------------------------------------------------

    private static final class FakeService extends VisualCompositionService {
        private final Map<ResourceLocation, GunDefinition> guns = new HashMap<>();
        private final Map<ResourceLocation, PluginDefinition> plugins = new HashMap<>();
        private final Map<ResourceLocation, Trait> traits = new HashMap<>();
        private final Map<ResourceLocation, StateDefinition> states = new HashMap<>();

        @Override
        protected Optional<GunDefinition> lookupGun(RegistryAccess ra, ResourceLocation gunId) {
            return Optional.ofNullable(guns.get(gunId));
        }

        @Override
        protected Optional<PluginDefinition> lookupPlugin(RegistryAccess ra, ResourceLocation pluginId) {
            return Optional.ofNullable(plugins.get(pluginId));
        }

        @Override
        protected Optional<Trait> lookupTrait(RegistryAccess ra, ResourceLocation traitId) {
            return Optional.ofNullable(traits.get(traitId));
        }

        @Override
        protected Stream<StateDefinition> allStateDefsWithVisualModifiers(RegistryAccess ra) {
            return states.values().stream().filter(d -> !d.visualModifiers().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private static final ResourceLocation GUN_ID = rl("m", "test_gun");
    private static final ResourceLocation PLUGIN_A_ID = rl("m", "plugin_a");
    private static final ResourceLocation PLUGIN_B_ID = rl("m", "plugin_b");
    private static final ResourceLocation TRAIT_T_ID = rl("m", "trait_t");
    private static final ResourceLocation STATE_KS = rl("m", "killstreak");

    private static ResourceLocation rl(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    /** Builds a {@link BulletSnapshot} with the given gun id and state map. */
    private static BulletSnapshot snap(ResourceLocation gunId, Map<ResourceLocation, Object> state) {
        return new BulletSnapshot(
                new HashMap<>(),
                new HashMap<>(),
                Holder.direct(new DamageType("test", 0.0f)),
                null,
                gunId,
                null,
                state == null ? new HashMap<>() : new HashMap<>(state));
    }

    /** Builds a {@link GunDefinition} whose only interesting field is {@code bulletStyle}. */
    private static GunDefinition gunWithStyle(BulletStyle style) {
        return new GunDefinition(
                Optional.empty(),
                rl("m", "gun_texture"),
                Optional.empty(),
                org.yanbwe.modularshoot.registry.gun.ShootTextureMode.PER_SHOT,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.of(style));
    }

    /** Builds a {@link PluginDefinition} with the given priority and bullet style. */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static PluginDefinition pluginWith(int priority, BulletStyle style) {
        return new PluginDefinition(
                List.of(),
                priority,
                rl("m", "icon"),
                List.of(),
                Map.of(),
                Optional.empty(),
                Optional.ofNullable(style),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static GunData gunData(ResourceLocation gunId, List<org.yanbwe.modularshoot.component.PluginInstance> plugins) {
        return new GunData(gunId, UUID.randomUUID(), plugins, 0, new net.minecraft.nbt.CompoundTag());
    }

    private static org.yanbwe.modularshoot.component.PluginInstance pluginInst(ResourceLocation id) {
        return new org.yanbwe.modularshoot.component.PluginInstance(id, UUID.randomUUID(), rl("m", "type"), false);
    }

    private static StateDefinition stateWith(int priority, int defaultVal,
                                             StateDefinition.StateVisualModifier... mods) {
        return new StateDefinition(
                StateDomain.BULLET,
                StateValueType.INT,
                defaultVal,
                org.yanbwe.modularshoot.state.StateDisplay.of("test", Optional.empty()),
                List.of(mods));
    }

    private static StateDefinition.StateVisualModifier when(int threshold, StateConditionEvaluator.Op op,
                                                            List<Modifier> modifiers) {
        return new StateDefinition.StateVisualModifier(
                new StateDefinition.VisualCondition(STATE_KS, Optional.empty(), op, threshold),
                modifiers);
    }

    /** Compose under a fake service with defaults and a snapshot whose gunId resolves to {@code gunData}. */
    private static ComposedBulletStyle compose(FakeService svc, BulletSnapshot snap, GunData gd) {
        return svc.compose(null, snap, gd);
    }

    // ------------------------------------------------------------------
    // Scale 连乘
    // ------------------------------------------------------------------

    @Test
    void scaleMultiplyMultipliesAcrossSources() {
        FakeService svc = new FakeService();
        BulletStyle gunStyle = new BulletStyle(Optional.empty(),
                List.of(new ScaleModifier(1.2f)));
        svc.guns.put(GUN_ID, gunWithStyle(gunStyle));
        svc.plugins.put(PLUGIN_A_ID, pluginWith(1,
                new BulletStyle(Optional.empty(), List.of(new ScaleModifier(1.5f)))));
        GunData gd = gunData(GUN_ID, List.of(pluginInst(PLUGIN_A_ID)));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(1.8f, result.renderScale(), 1e-6,
                "1.2 × 1.5 = 1.8 across gun + plugin sources");
    }

    // ------------------------------------------------------------------
    // Tint 通道连乘
    // ------------------------------------------------------------------

    @Test
    void tintMultiplyMultipliesChannels() {
        FakeService svc = new FakeService();
        BulletStyle gunStyle = new BulletStyle(Optional.empty(),
                List.of(new TintModifier(new Vector4f(0.5f, 1.0f, 0.5f, 1.0f))));
        svc.guns.put(GUN_ID, gunWithStyle(gunStyle));
        svc.plugins.put(PLUGIN_A_ID, pluginWith(1,
                new BulletStyle(Optional.empty(), List.of(new TintModifier(new Vector4f(1.0f, 0.6f, 1.0f, 1.0f))))));
        GunData gd = gunData(GUN_ID, List.of(pluginInst(PLUGIN_A_ID)));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        Vector4f t = result.composedTint();
        assertEquals(0.5f, t.x, 1e-6);
        assertEquals(0.6f, t.y, 1e-6);
        assertEquals(0.5f, t.z, 1e-6);
        assertEquals(1.0f, t.w, 1e-6);
    }

    // ------------------------------------------------------------------
    // attach_layer 全保留 + 顺序
    // ------------------------------------------------------------------

    @Test
    void attachLayerAllPreservedInOrder() {
        FakeService svc = new FakeService();
        AttachLayerModifier l1 = new AttachLayerModifier(
                BulletStyle.RenderMode.BILLBOARD, rl("m", "flame"), null,
                false, true, new Vec3(0, 0, -0.1), 1.0f, new Vector4f(1, 1, 1, 1));
        BulletStyle gunStyle = new BulletStyle(Optional.empty(), List.of(l1));
        svc.guns.put(GUN_ID, gunWithStyle(gunStyle));
        // Use a real gunData so collectGun runs (spec §4.1: gun sources queried via gunData).
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(1, result.layers().size(), "gun layer preserved");
        // plus one via trait
        AttachLayerModifier l2 = new AttachLayerModifier(
                BulletStyle.RenderMode.BILLBOARD, rl("m", "aura"), null,
                false, false, Vec3.ZERO, 1.0f, new Vector4f(1, 1, 1, 1));
        svc.traits.put(TRAIT_T_ID, new Trait(false, "", Optional.empty(), Optional.empty(),
                Optional.empty(), false, 0, List.of(l2)));
        // re-snap with trait active
        Map<ResourceLocation, Boolean> traits = Map.of(TRAIT_T_ID, true);
        BulletSnapshot snap = new BulletSnapshot(new HashMap<>(), traits,
                Holder.direct(new DamageType("test", 0.0f)), null, GUN_ID, null, new HashMap<>());
        ComposedBulletStyle t2 = compose(svc, snap, gd);
        assertEquals(2, t2.layers().size(), "gun + trait layer both preserved");
        assertEquals(rl("m", "flame"), t2.layers().get(0).texture(), "gun layer first");
        assertEquals(rl("m", "aura"), t2.layers().get(1).texture(), "trait layer second");
    }

    // ------------------------------------------------------------------
    // Base last-wins by priority
    // ------------------------------------------------------------------

    @Test
    void baseLastWinsByPriority() {
        FakeService svc = new FakeService();
        BulletStyle.Base gunBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "gun_tex"), null);
        svc.guns.put(GUN_ID, gunWithStyle(new BulletStyle(Optional.of(gunBase), List.of())));
        BulletStyle.Base pluginBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "plugin_tex"), null);
        svc.plugins.put(PLUGIN_A_ID, pluginWith(5,
                new BulletStyle(Optional.of(pluginBase), List.of())));
        GunData gd = gunData(GUN_ID, List.of(pluginInst(PLUGIN_A_ID)));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(rl("m", "plugin_tex"), result.base().texture(),
                "higher-priority plugin base wins over gun base (priority 0)");
    }

    // ------------------------------------------------------------------
    // 同优先级后装赢
    // ------------------------------------------------------------------

    @Test
    void baseTieBreakInstallOrder() {
        FakeService svc = new FakeService();
        BulletStyle.Base gunBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "gun_tex"), null);
        svc.guns.put(GUN_ID, gunWithStyle(new BulletStyle(Optional.of(gunBase), List.of())));
        BulletStyle.Base aBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "a_tex"), null);
        BulletStyle.Base bBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "b_tex"), null);
        svc.plugins.put(PLUGIN_A_ID, pluginWith(3, new BulletStyle(Optional.of(aBase), List.of())));
        svc.plugins.put(PLUGIN_B_ID, pluginWith(3, new BulletStyle(Optional.of(bBase), List.of())));
        // Install order: A first, B later -> B wins at equal priority
        GunData gd = gunData(GUN_ID, List.of(pluginInst(PLUGIN_A_ID), pluginInst(PLUGIN_B_ID)));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(rl("m", "b_tex"), result.base().texture(),
                "later-installed plugin wins base at equal priority");
    }

    // ------------------------------------------------------------------
    // 全缺失 base -> FALLBACK_BASE
    // ------------------------------------------------------------------

    @Test
    void noBaseCandidatesFallback() {
        FakeService svc = new FakeService();
        svc.guns.put(GUN_ID, gunWithStyle(new BulletStyle(Optional.empty(), List.of())));
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(ComposedBulletStyle.FALLBACK_BASE.texture(), result.base().texture());
        assertEquals(ComposedBulletStyle.FALLBACK_BASE.renderMode(), result.base().renderMode());
    }

    // ------------------------------------------------------------------
    // 全缺失 (null snapshot) -> DEFAULT
    // ------------------------------------------------------------------

    @Test
    void nullSnapshotReturnsDefault() {
        FakeService svc = new FakeService();
        ComposedBulletStyle result = svc.compose(null, null, null);
        assertSame(ComposedBulletStyle.DEFAULT, result);
    }

    // ------------------------------------------------------------------
    // Plugin 未注册 -> skip
    // ------------------------------------------------------------------

    @Test
    void pluginUnregisteredSkips() {
        FakeService svc = new FakeService();
        BulletStyle.Base gunBase = new BulletStyle.Base(BulletStyle.RenderMode.BILLBOARD,
                rl("m", "gun_tex"), null);
        svc.guns.put(GUN_ID, gunWithStyle(new BulletStyle(Optional.of(gunBase), List.of())));
        // Plugin remaining unregistered
        GunData gd = gunData(GUN_ID, List.of(pluginInst(rl("m", "ghost"))));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(rl("m", "gun_tex"), result.base().texture(),
                "unregistered plugin skipped; gun base retained");
        assertEquals(1.0f, result.renderScale(), 1e-6);
    }

    // ------------------------------------------------------------------
    // NaN / non-positive scale -> skip + 连乘其余
    // ------------------------------------------------------------------

    @Test
    void nanScaleSkips() {
        FakeService svc = new FakeService();
        BulletStyle style = new BulletStyle(Optional.empty(),
                List.of(new ScaleModifier(1.5f),
                        new ScaleModifier(Float.NaN),
                        new ScaleModifier(-2.0f)));
        svc.guns.put(GUN_ID, gunWithStyle(style));
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(1.5f, result.renderScale(), 1e-6,
                "NaN and negative scale skipped; valid factor preserved");
    }

    // ------------------------------------------------------------------
    // tint 超出 [0,1] -> clamp 无 WARN
    // ------------------------------------------------------------------

    @Test
    void tintClampsAboveOneNoWarn() {
        FakeService svc = new FakeService();
        BulletStyle style = new BulletStyle(Optional.empty(),
                List.of(new TintModifier(new Vector4f(1.5f, 1.0f, 1.0f, 1.0f))));
        svc.guns.put(GUN_ID, gunWithStyle(style));
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        Vector4f t = result.composedTint();
        assertEquals(1.0f, t.x, 1e-6, "clamp >1 to 1 without failure");
    }

    // ------------------------------------------------------------------
    // tint 负数 -> skip 不污染其他通道
    // ------------------------------------------------------------------

    @Test
    void tintNegativeSkips() {
        FakeService svc = new FakeService();
        BulletStyle style = new BulletStyle(Optional.empty(),
                List.of(new TintModifier(new Vector4f(-1.0f, 0.5f, 0.5f, 1.0f)),
                        new TintModifier(new Vector4f(0.9f, 0.9f, 0.9f, 1.0f))));
        svc.guns.put(GUN_ID, gunWithStyle(style));
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        Vector4f t = result.composedTint();
        assertEquals(0.9f, t.x, 1e-6, "negative-tint entry skipped");
        assertEquals(0.45f, t.y, 1e-6);
        assertEquals(0.45f, t.z, 1e-6);
        assertEquals(1.0f, t.w, 1e-6);
    }

    // ------------------------------------------------------------------
    // trait 活动时贡献 modifiers
    // ------------------------------------------------------------------

    @Test
    void activeTraitContributesModifiers() {
        FakeService svc = new FakeService();
        svc.traits.put(TRAIT_T_ID, new Trait(false, "", Optional.empty(), Optional.empty(),
                Optional.empty(), false, 0, List.of(new TintModifier(new Vector4f(0.2f, 1.0f, 1.0f, 1.0f)))));
        Map<ResourceLocation, Boolean> traits = Map.of(TRAIT_T_ID, true);
        BulletSnapshot snap = new BulletSnapshot(new HashMap<>(), traits,
                Holder.direct(new DamageType("test", 0.0f)), null, GUN_ID, null, new HashMap<>());
        ComposedBulletStyle result = compose(svc, snap, null);
        assertEquals(0.2f, result.composedTint().x, 1e-6, "active trait contributes tint");
    }

    @Test
    void inactiveTraitDoesNotContribute() {
        FakeService svc = new FakeService();
        svc.traits.put(TRAIT_T_ID, new Trait(false, "", Optional.empty(), Optional.empty(),
                Optional.empty(), false, 0, List.of(new TintModifier(new Vector4f(0.2f, 1.0f, 1.0f, 1.0f)))));
        Map<ResourceLocation, Boolean> traits = Map.of(TRAIT_T_ID, false);
        BulletSnapshot snap = new BulletSnapshot(new HashMap<>(), traits,
                Holder.direct(new DamageType("test", 0.0f)), null, GUN_ID, null, new HashMap<>());
        ComposedBulletStyle result = compose(svc, snap, null);
        assertEquals(1.0f, result.composedTint().x, 1e-6, "inactive trait contributes nothing");
    }

    // ------------------------------------------------------------------
    // state condition true -> modifier 入组合
    // ------------------------------------------------------------------

    @Test
    void stateConditionTrueAddsModifiers() {
        FakeService svc = new FakeService();
        svc.states.put(STATE_KS, stateWith(0, 0,
                when(3, StateConditionEvaluator.Op.GE,
                        List.of(new ScaleModifier(1.2f)))));
        // Snapshot state killstreak = 5 >= 3 -> scale active
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of(STATE_KS, 5)), null);
        assertEquals(1.2f, result.renderScale(), 1e-6);
    }

    // ------------------------------------------------------------------
    // state condition false -> 不入
    // ------------------------------------------------------------------

    @Test
    void stateConditionFalseSkipsModifiers() {
        FakeService svc = new FakeService();
        svc.states.put(STATE_KS, stateWith(0, 0,
                when(3, StateConditionEvaluator.Op.GE,
                        List.of(new ScaleModifier(1.2f)))));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of(STATE_KS, 1)), null);
        assertEquals(1.0f, result.renderScale(), 1e-6, "condition false -> no scale");
    }

    // ------------------------------------------------------------------
    // state 用注册默认值回退（注册的默认 0；5 → false，defaultval 0 → false）
    // ------------------------------------------------------------------

    @Test
    void stateMissingValueUsesRegisteredDefault() {
        FakeService svc = new FakeService();
        // registered default = 4
        svc.states.put(STATE_KS, stateWith(0, 4,
                when(3, StateConditionEvaluator.Op.GE,
                        List.of(new ScaleModifier(1.2f)))));
        // Snapshot state map empty -> per-bullet null, per-gun null -> default 4 >= 3 -> active
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, new HashMap<>()), null);
        assertEquals(1.2f, result.renderScale(), 1e-6, "absent state uses registered default; 4 >= 3");
    }

    // ------------------------------------------------------------------
    // 未注册 state condition -> skip
    // ------------------------------------------------------------------

    @Test
    void stateUnregisteredSkips() {
        FakeService svc = new FakeService();
        // State never registered in tests; condition references unregistered id
        StateDefinition.StateVisualModifier mod = new StateDefinition.StateVisualModifier(
                new StateDefinition.VisualCondition(rl("m", "ghost_state"),
                        Optional.empty(), StateConditionEvaluator.Op.GE, 1),
                List.of(new ScaleModifier(1.2f)));
        svc.states.put(STATE_KS, stateWith(0, 0, mod));
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, new HashMap<>()), null);
        assertEquals(1.0f, result.renderScale(), 1e-6, "unregistered state condition skipped");
    }

    // ------------------------------------------------------------------
    // 未知 modifier 类型 -> 跳过，其他正常聚合
    // ------------------------------------------------------------------

    @Test
    void unknownModifierTypeSkips() {
        FakeService svc = new FakeService();
        BulletStyle style = new BulletStyle(Optional.empty(),
                List.of(new ScaleModifier(1.2f),
                        new org.yanbwe.modularshoot.registry.gun.UnsupportedModifier("future_type")));
        svc.guns.put(GUN_ID, gunWithStyle(style));
        GunData gd = gunData(GUN_ID, List.of());
        ComposedBulletStyle result = compose(svc, snap(GUN_ID, Map.of()), gd);
        assertEquals(1.2f, result.renderScale(), 1e-6, "unknown-type sentinel skipped");
        assertTrue(result.layers().isEmpty());
    }
}