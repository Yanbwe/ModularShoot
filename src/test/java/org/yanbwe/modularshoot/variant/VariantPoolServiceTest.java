package org.yanbwe.modularshoot.variant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.ShootTextureMode;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;
import org.yanbwe.modularshoot.registry.variant.VariantDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link VariantPoolService} (机制四 §6.3/§6.4).
 * No real {@code RegistryAccess} is required:
 * <ul>
 *   <li>{@code calculateWeight} is a pure function (原版
 *       {@code AttributeInstance.calculateValue()} 三阶段语义);</li>
 *   <li>{@code roll} tests declare variants via {@code gunDef.variants()}
 *       with an empty plugin list and no contributors, so no registry lookup
 *       runs ({@code ra} may be {@code null});</li>
 *   <li>{@code apply} is exercised through a {@link FakeService} subclass
 *       that overrides the protected
 *       {@link VariantPoolService#lookupVariant} seam to serve in-memory
 *       definitions — the same seam pattern as
 *       {@code VisualCompositionServiceTest}.</li>
 * </ul>
 */
class VariantPoolServiceTest {

    private static final ResourceLocation VARIANT_A = rl("modularshoot", "variant_a");
    private static final ResourceLocation VARIANT_B = rl("modularshoot", "variant_b");
    private static final ResourceLocation HIT_DAMAGE = rl("modularshoot", "hit_damage");
    private static final ResourceLocation FIRE_RATE = rl("modularshoot", "fire_rate");
    private static final ResourceLocation TRAIT_A = rl("modularshoot", "trait_a");
    private static final ResourceLocation TRAIT_B = rl("modularshoot", "trait_b");

    @BeforeEach
    void clearContributors() {
        VariantContributorRegistry.clear();
    }

    private static ResourceLocation rl(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    /** Builds an {@link AttributeModifier} with the given amount and operation. */
    private static AttributeModifier mod(double amount, AttributeModifier.Operation op) {
        return new AttributeModifier(rl("modularshoot", "test"), amount, op);
    }

    /** Builds a {@link GunDefinition} whose only interesting field is {@code variants}. */
    private static GunDefinition gunWithVariants(Map<ResourceLocation, Double> variants) {
        return new GunDefinition(
                Optional.empty(),
                rl("m", "gun_texture"),
                Optional.empty(),
                ShootTextureMode.PER_SHOT,
                TextureScaleMode.AUTO,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Optional.empty(),
                variants);
    }

    /** Builds an empty-instance {@link GunData} (no plugins → no registry lookups). */
    private static GunData gunData() {
        return new GunData(rl("m", "gun"), UUID.randomUUID(), List.of(), 0, new CompoundTag());
    }

    /** Builds a {@link BulletSnapshot} with the given stats/traits (no state, no identity). */
    private static BulletSnapshot snap(Map<ResourceLocation, Double> stats, Map<ResourceLocation, Boolean> traits) {
        return new BulletSnapshot(stats, traits, null, null, null, null, new HashMap<>());
    }

    // ------------------------------------------------------------------
    // calculateWeight 三阶段纯函数（规格 §6.3）
    // ------------------------------------------------------------------

    @Test
    void weightAddValueOnly() {
        double result = VariantPoolService.calculateWeight(10.0,
                List.of(mod(5.0, AttributeModifier.Operation.ADD_VALUE)));
        assertEquals(15.0, result, 1e-9, "10 + 5 = 15");
    }

    @Test
    void weightMultipliedBaseScalesFullBase() {
        double result = VariantPoolService.calculateWeight(10.0,
                List.of(mod(0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));
        assertEquals(15.0, result, 1e-9, "10 × 1.5 = 15");
    }

    @Test
    void weightMultipliedBaseIncludesAddValue() {
        // 防"只乘纯 base"的错误实现：错误实现会得 10×1.5 + 5 = 20
        double result = VariantPoolService.calculateWeight(10.0,
                List.of(mod(5.0, AttributeModifier.Operation.ADD_VALUE),
                        mod(0.5, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));
        assertEquals(22.5, result, 1e-9, "d = 15, 15 × 1.5 = 22.5");
    }

    @Test
    void weightMultipliedTotalScalesTotal() {
        double result = VariantPoolService.calculateWeight(10.0,
                List.of(mod(5.0, AttributeModifier.Operation.ADD_VALUE),
                        mod(0.1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)));
        assertEquals(16.5, result, 1e-9, "15 × 1.1 = 16.5");
    }

    @Test
    void zeroBaseWithMultipliedBaseStaysZero() {
        // "火元素饰品对非火枪无效"语义：基础 0 且无加值时 d = 0 → 结果恒 0（规格 §6.3）
        double result = VariantPoolService.calculateWeight(0.0,
                List.of(mod(1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE)));
        assertEquals(0.0, result, 1e-9, "0 × (1 + 1.0) = 0");
    }

    // ------------------------------------------------------------------
    // roll 池语义（规格 §6.4）
    // ------------------------------------------------------------------

    @Test
    void rollEmptyPoolReturnsEmpty() {
        // 空 variants 枪械且无任何来源：池为空，但"未声明池"触发普通弹兜底（权重 1.0）
        // → roll 必落兜底区间 → 普通弹（静默）。结果与无兜底时一致，语义由
        // undeclaredPoolGetsNormalFallback 验证。
        Optional<ResourceLocation> rolled = VariantPoolService.roll(
                null, RandomSource.create(42), gunWithVariants(Map.of()), gunData());
        assertTrue(rolled.isEmpty(), "空池 → 普通弹（静默）");
    }

    @Test
    void rollAllZeroWeightsReturnsEmpty() {
        Optional<ResourceLocation> rolled = VariantPoolService.roll(
                null, RandomSource.create(42),
                gunWithVariants(Map.of(VARIANT_A, 0.0, VARIANT_B, 0.0)), gunData());
        assertTrue(rolled.isEmpty(), "总权重 ≤ 0 → 普通弹（静默）");
    }

    @Test
    void rollDeterministicWithSeededRandom() {
        GunDefinition gunDef = gunWithVariants(Map.of(VARIANT_A, 1.0, VARIANT_B, 2.0));
        GunData data = gunData();
        // 固定 seed → 可复现结果
        long seed = 12345L;
        Optional<ResourceLocation> first = VariantPoolService.roll(null, RandomSource.create(seed), gunDef, data);
        Optional<ResourceLocation> second = VariantPoolService.roll(null, RandomSource.create(seed), gunDef, data);
        assertEquals(first, second, "同一种子必须复现相同选举结果");
        assertTrue(first.isPresent(), "权重 1:2 的池必然有选举结果");
        assertTrue(first.get().equals(VARIANT_A) || first.get().equals(VARIANT_B));

        // 单个固定种子的随机源连续采样 1000 枪（与生产路径一致：每发消耗 level
        // random 的下一个值），LCG 状态连续演化 → 标准均匀序列，权重 1:2 → B 约
        // 占 2/3。固定种子 → 结果确定，无 flaky。
        // 注意：不能用 RandomSource.create(i)（连续小整数种子）逐个采样——Legacy
        // RandomSource 经 XOR 种子混淆后，相邻小整数种子的首个输出高度聚集于同一
        // 窗口（实测 seed 0-9 的 nextDouble 全 ≈ 0.73），统计会 0/1000 全偏。
        RandomSource random = RandomSource.create(12345L);
        int bCount = 0;
        for (int i = 0; i < 1000; i++) {
            Optional<ResourceLocation> rolled = VariantPoolService.roll(null, random, gunDef, data);
            if (rolled.isPresent() && rolled.get().equals(VARIANT_B)) {
                bCount++;
            }
        }
        assertTrue(bCount > 500 && bCount < 850,
                "weight-2 variant chosen ~2/3 of 1000 draws, got " + bCount);
    }

    @Test
    void undeclaredPoolGetsNormalFallback() {
        // 规格 §6.4 v1.2：枪械**未声明** variants 时，池中默认存在权重 1.0 的
        // "普通子弹"兜底候选。否则"给普通枪加 50% 火球插件"会因单候选池恒 100%
        // 触发（这不科学）。本例：贡献者引入 A（hint 1.0 + ADD_VALUE 1.0 → 2.0），
        // 池 = {A: 2.0, 普通弹: 1.0} → A 命中率 2/3。
        FakeService fake = new FakeService();
        fake.variants.put(VARIANT_A, new VariantDefinition(
                1.0,
                Map.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty()));
        VariantContributorRegistry.register(
                sink -> sink.add(VARIANT_A, mod(1.0, AttributeModifier.Operation.ADD_VALUE)));

        RandomSource random = RandomSource.create(12345L);
        int aCount = 0;
        for (int i = 0; i < 1500; i++) {
            if (fake.rollImpl(null, random, gunWithVariants(Map.of()), gunData())
                    .filter(VARIANT_A::equals).isPresent()) {
                aCount++;
            }
        }
        assertTrue(aCount > 900 && aCount < 1100,
                "A 命中率应约 2/3（1500 次 ≈ 1000），实际 " + aCount);
    }

    @Test
    void declaredPoolHasNoNormalFallback() {
        // 枪械**声明了** variants → 无普通弹兜底：单候选池恒 100%
        // （普通弹兜底只属于未声明池，声明池按声明权重精确计算）。
        GunDefinition gunDef = gunWithVariants(Map.of(VARIANT_A, 1.0));
        RandomSource random = RandomSource.create(12345L);
        for (int i = 0; i < 500; i++) {
            Optional<ResourceLocation> rolled = VariantPoolService.roll(null, random, gunDef, gunData());
            assertTrue(rolled.isPresent() && rolled.get().equals(VARIANT_A),
                    "声明池单候选恒命中（无兜底区间），第 " + i + " 次采样");
        }
    }

    @Test
    void contributorOnlyVariantSharesPoolWithNormalFallback() {
        // 仅由贡献者引入的变体：池中以自身 weight_hint 作为基础权重兜底（设计决策 1）。
        // 内存定义 weightHint = 3.0，贡献者再加 ADD_VALUE +1.0 → 最终权重 4.0。
        // 枪械未声明池 → 普通弹兜底 1.0 同在 → 池 = {A: 4.0, 普通弹: 1.0}，
        // A 命中率 80%、普通弹（empty）20%——两者都必须出现。
        FakeService fake = new FakeService();
        fake.variants.put(VARIANT_A, new VariantDefinition(
                3.0,
                Map.of(),
                Map.of(),
                Optional.empty(),
                Optional.empty()));
        VariantContributorRegistry.register(
                sink -> sink.add(VARIANT_A, mod(1.0, AttributeModifier.Operation.ADD_VALUE)));

        // 空 variants 枪械 + 空插件 GunData → 池唯一候选即贡献者引入的 VARIANT_A。
        // @BeforeEach 已 clear() 隔离；空插件列表下 assemble 的 filterValidPlugins
        // 不会触碰 null ra，直接传 null 即可。
        RandomSource random = RandomSource.create(12345L);
        int aCount = 0;
        int emptyCount = 0;
        for (int i = 0; i < 1000; i++) {
            Optional<ResourceLocation> rolled = fake.rollImpl(
                    null, random, gunWithVariants(Map.of()), gunData());
            if (rolled.filter(VARIANT_A::equals).isPresent()) {
                aCount++;
            } else if (rolled.isEmpty()) {
                emptyCount++;
            }
        }
        assertTrue(aCount > 700 && aCount < 900,
                "A 命中率应约 80%（1000 次 ≈ 800），实际 " + aCount);
        assertTrue(emptyCount > 100 && emptyCount < 300,
                "普通弹兜底区间应约 20%（1000 次 ≈ 200），实际 " + emptyCount);
    }

    // ------------------------------------------------------------------
    // apply 改写快照（traits 合并 / stats 覆盖声明键 / 样式暂存）
    // ------------------------------------------------------------------

    @Test
    void applyOverwritesStatsAndMergesTraitsAndStagesStyle() {
        FakeService fake = new FakeService();
        BulletStyle style = new BulletStyle(Optional.empty(), List.of());
        fake.variants.put(VARIANT_A, new VariantDefinition(
                1.0,
                Map.of(TRAIT_B, true),
                Map.of(HIT_DAMAGE, 15.0),
                Optional.empty(),                 // damage_type 需要真实 RegistryAccess，本用例不测
                Optional.of(style)));

        BulletSnapshot snapshot = snap(
                new HashMap<>(Map.of(HIT_DAMAGE, 5.0, FIRE_RATE, 2.0)),
                new HashMap<>(Map.of(TRAIT_A, true)));

        fake.applyImpl(VARIANT_A, null, snapshot);

        assertEquals(15.0, snapshot.getStat(HIT_DAMAGE), 1e-9, "声明键 stats 覆盖");
        assertEquals(2.0, snapshot.getStat(FIRE_RATE), 1e-9, "未声明键保留");
        assertTrue(snapshot.getTrait(TRAIT_A), "未声明 trait 保留（合并语义）");
        assertTrue(snapshot.getTrait(TRAIT_B), "声明 trait 合并入快照");
        assertSame(style, snapshot.getVariantStyleOverride(), "bullet_style_override 暂存进快照");
    }

    @Test
    void applyMissingVariantIsNoOp() {
        FakeService fake = new FakeService();     // 内存 map 为空 → lookupVariant 恒 empty
        BulletSnapshot snapshot = snap(new HashMap<>(Map.of(HIT_DAMAGE, 5.0)), new HashMap<>());
        fake.applyImpl(VARIANT_A, null, snapshot);
        assertEquals(5.0, snapshot.getStat(HIT_DAMAGE), 1e-9, "未注册变体 → WARN + 不改写快照");
        assertNull(snapshot.getVariantStyleOverride(), "未注册变体不暂存样式");
    }

    // ------------------------------------------------------------------
    // Fake service: stubs the protected lookupVariant seam via an in-memory map.
    // ------------------------------------------------------------------

    private static final class FakeService extends VariantPoolService {
        private final Map<ResourceLocation, VariantDefinition> variants = new HashMap<>();

        @Override
        protected Optional<VariantDefinition> lookupVariant(
                net.minecraft.core.RegistryAccess ra, ResourceLocation variantId) {
            return Optional.ofNullable(variants.get(variantId));
        }
    }
}
