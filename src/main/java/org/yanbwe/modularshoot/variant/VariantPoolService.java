package org.yanbwe.modularshoot.variant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.variant.VariantDefinition;
import org.yanbwe.modularshoot.registry.variant.VariantRegistry;

/**
 * Per-shot random variant pool service (机制四 §6.3/§6.4).
 *
 * <p>Every shot assembles the variant pool live from three sources (规格
 * §6.2 来源表): the gun definition's {@code variants}, each valid installed
 * plugin's {@code adds_variants} (same variant declared by several sources
 * sums via {@code merge(id, v, Double::sum)}), and variant ids introduced by
 * {@code registerVariantContributor} weight modifiers — the latter fall back
 * to the variant's own {@code base_weight} when nobody declared them. Each
 * candidate's final weight comes from the three-stage pure function
 * {@link #calculateWeight}; a single roll then picks one variant for one
 * pellet (逐弹丸语义, 规格 §6.4 — the shooting engine calls
 * {@link #rollAndApply} once per pellet, so pellets of one shot may end up
 * with different variants or a normal bullet). No selection (empty pool or
 * total weight &le; 0) → 普通弹 (silent). The selected variant rewrites the
 * frozen snapshot: traits merge, stats overwrite only declared keys,
 * {@code damage_type} overrides the ammo preset, and
 * {@code bullet_style_override} is staged for
 * {@code VisualCompositionService} (变体优先, 规格 §6.4).</p>
 *
 * <h2>Static facade + lookup seam (测试扩展点)</h2>
 *
 * <p>The production API is the static methods {@link #roll}, {@link #apply}
 * and {@link #rollAndApply}, which delegate to the shared {@link #INSTANCE}.
 * The only {@code RegistryAccess} touch point is the protected
 * {@link #lookupVariant} seam; pure-logic tests subclass this class in the
 * test source set and override the seam with in-memory definitions — no MC
 * runtime is required (mirrors {@code VisualCompositionService}'s protected
 * lookup pattern). The constructor is protected so the class cannot be
 * instantiated by ordinary callers.</p>
 */
public class VariantPoolService {

    /**
     * 未声明变体池的枪械的默认"普通子弹"兜底权重（规格 §6.4）。枪械未声明
     * {@code variants} 时，池中默认存在权重 1.0 的普通弹候选——否则"给普通枪加
     * 50% 火球插件"会因单候选池恒 100% 触发（这不科学）；有兜底后该场景按
     * {@code 火球权重 : 1.0} 计算概率。
     */
    static final double NORMAL_FALLBACK_WEIGHT = 1.0;

    /** Shared singleton backing the static facade (seam-aware dispatch). */
    public static final VariantPoolService INSTANCE = new VariantPoolService();

    protected VariantPoolService() {
    }

    /**
     * Resolves a variant definition by id (override point for tests).
     *
     * @param ra        the runtime registry view (ignored by stubs; may be
     *                  {@code null} under test, in which case the default
     *                  returns empty)
     * @param variantId the variant definition id, never {@code null}
     * @return the variant definition, or empty when unregistered
     */
    protected Optional<VariantDefinition> lookupVariant(
            @Nullable RegistryAccess ra, ResourceLocation variantId) {
        if (ra == null) {
            return Optional.empty();
        }
        return VariantRegistry.getVariant(ra, variantId);
    }

    // ------------------------------------------------------------------
    // Static API (production call sites)
    // ------------------------------------------------------------------

    /**
     * Computes a variant's final weight from its base weight and the
     * contributor modifiers, replicating vanilla
     * {@code AttributeInstance.calculateValue()} semantics in three stages
     * (规格 §6.3):
     * <ol>
     *   <li>{@code d = baseWeight + Σ ADD_VALUE};</li>
     *   <li>{@code scaled = d + d × Σ ADD_MULTIPLIED_BASE} — the multiplier
     *       scales {@code d} including the ADD_VALUE part, so a zero base
     *       with no add-value stays {@code 0} ("火元素饰品对非火枪无效"
     *       语义);</li>
     *   <li>{@code result = scaled × (1 + Σ ADD_MULTIPLIED_TOTAL)} — applies
     *       to the whole.</li>
     * </ol>
     *
     * @param baseWeight the base weight declared by gun/plugin (or the
     *                   variant's {@code base_weight} fallback)
     * @param modifiers  the contributor weight modifiers for this variant,
     *                   in registration order; may be empty
     * @return the final weight; {@code 0.0} when the base is zero and no
     *         ADD_VALUE modifier is present
     */
    public static double calculateWeight(double baseWeight, List<AttributeModifier> modifiers) {
        double add = 0.0, mulBase = 0.0, mulTotal = 0.0;
        for (AttributeModifier m : modifiers) {
            switch (m.operation()) {
                case ADD_VALUE -> add += m.amount();
                case ADD_MULTIPLIED_BASE -> mulBase += m.amount();
                case ADD_MULTIPLIED_TOTAL -> mulTotal += m.amount();
            }
        }
        double d = baseWeight + add;             // 阶段一：基础 + 加值
        double scaled = d + d * mulBase;         // 阶段二：e += d × ADD_MULTIPLIED_BASE（乘 d，与 MC 原版一致）
        return scaled * (1.0 + mulTotal);        // 阶段三：作用于整体
    }

    /**
     * Assembles the per-shot variant pool and rolls once (逐弹丸语义, 规格
     * §6.4 — one call = one pellet's independent election). Entries
     * whose final weight is non-positive are excluded from the candidates;
     * a gun that declares no {@code variants} gets the default normal-bullet
     * fallback ({@link #NORMAL_FALLBACK_WEIGHT}, 规格 §6.4). An empty
     * candidate list, a declared pool with total weight &le; 0, or a roll
     * landing on the fallback interval all yield {@code Optional.empty()} —
     * the pellet proceeds as a normal bullet (静默).
     *
     * @param ra      the runtime registry view
     * @param random  the per-shot random source (server level random)
     * @param gunDef  the gun definition declaring {@code variants}
     * @param gunData the gun data carrying the installed plugin list
     * @return the rolled variant id, or empty for a normal bullet
     */
    public static Optional<ResourceLocation> roll(
            RegistryAccess ra, RandomSource random, GunDefinition gunDef, GunData gunData) {
        return INSTANCE.rollImpl(ra, random, gunDef, gunData);
    }

    /**
     * Applies a variant definition onto the frozen snapshot (traits merge /
     * stats overwrite / damage_type override / style staging). A missing
     * definition logs a WARN and leaves the snapshot untouched.
     *
     * @param variantId the rolled variant id
     * @param ra        the runtime registry view
     * @param snapshot  the frozen per-shot snapshot to rewrite (mutated)
     */
    public static void apply(ResourceLocation variantId, RegistryAccess ra, BulletSnapshot snapshot) {
        INSTANCE.applyImpl(variantId, ra, snapshot);
    }

    /**
     * Rolls the variant pool once and applies the winner to the snapshot
     * (规格 §6.4 逐弹丸语义). The shooting engine calls this once per
     * pellet, so each pellet of a shot gets an independent election. The
     * snapshot it rewrites is typically a per-pellet {@code copy()}; the
     * ammo damage type has already been resolved by {@code buildSnapshot},
     * so a variant's {@code damage_type} overrides the ammo preset
     * (变体优先). No selection → normal bullet (silent).
     *
     * @param player   the shooting server player (registry access + level random)
     * @param snapshot the frozen per-shot snapshot to rewrite (mutated)
     * @param gunData  the gun data (installed plugins)
     * @param gunDef   the gun definition (declared variants)
     */
    public static void rollAndApply(
            ServerPlayer player, BulletSnapshot snapshot, GunData gunData, GunDefinition gunDef) {
        INSTANCE.rollAndApplyImpl(player, snapshot, gunData, gunDef);
    }

    /**
     * Previews the per-shot variant pool without rolling (供
     * {@code /modularshoot variants} 调试命令展示)。Returns every candidate
     * with its final weight, in declaration order, plus the normal-bullet
     * fallback entry when the gun declares no {@code variants} (规格 §6.4
     * ). Percentages are derived by the caller: {@code P = finalWeight /
     * Σ finalWeights}.
     *
     * @param ra      the runtime registry view
     * @param gunDef  the gun definition declaring {@code variants}
     * @param gunData the gun data carrying the installed plugin list
     * @return the ordered pool preview; empty when the declared pool has no
     *         positive-weight candidate and no fallback applies
     */
    public static List<PoolEntry> previewPool(
            RegistryAccess ra, GunDefinition gunDef, GunData gunData) {
        return INSTANCE.previewPoolImpl(ra, gunDef, gunData);
    }

    // ------------------------------------------------------------------
    // Instance implementations (seam-aware; tests subclass and override
    // lookupVariant, then call these directly)
    // ------------------------------------------------------------------

    /**
     * Instance implementation backing {@link #roll}; identical semantics,
     * dispatched through the instance so the {@link #lookupVariant} seam is
     * honoured.
     */
    protected Optional<ResourceLocation> rollImpl(
            RegistryAccess ra, RandomSource random, GunDefinition gunDef, GunData gunData) {
        PoolBuild build = buildPool(ra, gunDef, gunData);
        if (build.total() <= 0.0) {
            return Optional.empty();   // 声明池全非正权重（无兜底）→ 普通弹
        }
        double r = random.nextDouble() * build.total();
        double cumulative = 0.0;
        for (WeightedCandidate c : build.candidates()) {
            cumulative += c.weight();
            if (r < cumulative) {
                return Optional.of(c.id());
            }
        }
        return Optional.empty();   // 落在普通弹兜底区间（或浮点舍入边界）→ 普通弹
    }

    /**
     * Builds the per-shot candidate list once (shared by {@link #rollImpl}
     * and {@link #previewPoolImpl}): assembles the pool, computes each
     * candidate's final weight via {@link #calculateWeight}, excludes
     * non-positive weights, and adds the normal-bullet fallback interval
     * ({@link #NORMAL_FALLBACK_WEIGHT}) when the gun declares no
     * {@code variants} (规格 §6.4).
     *
     * @param ra      the runtime registry view
     * @param gunDef  the gun definition declaring {@code variants}
     * @param gunData the gun data carrying the installed plugin list
     * @return the candidate list (positive weights only), the total weight
     *         (fallback included when applicable) and whether the fallback
     *         applies
     */
    private PoolBuild buildPool(RegistryAccess ra, GunDefinition gunDef, GunData gunData) {
        Map<ResourceLocation, List<AttributeModifier>> contribMods = VariantContributorRegistry.collect();
        Map<ResourceLocation, Double> pool = assemble(ra, gunDef, gunData, contribMods);
        List<WeightedCandidate> candidates = new ArrayList<>(pool.size());
        double total = 0.0;
        for (Map.Entry<ResourceLocation, Double> e : pool.entrySet()) {
            double w = calculateWeight(e.getValue(), contribMods.getOrDefault(e.getKey(), List.of()));
            if (w > 0.0) {
                candidates.add(new WeightedCandidate(e.getKey(), w));
                total += w;
            }
        }
        // 普通弹兜底（规格 §6.4）：枪械未声明 variants 时，池中默认存在
        // NORMAL_FALLBACK_WEIGHT 权重的"普通子弹"候选。roll 落在候选累积权重之外
        // （含本区间）→ 返回 empty（普通弹，静默）。声明了池的枪械无兜底，概率
        // 严格按声明权重计算。
        boolean normalFallback = gunDef.variants().isEmpty();
        if (normalFallback) {
            total += NORMAL_FALLBACK_WEIGHT;
        }
        return new PoolBuild(candidates, total, normalFallback);
    }

    /**
     * Instance implementation backing {@link #apply}; identical semantics,
     * dispatched through the instance so the {@link #lookupVariant} seam is
     * honoured.
     */
    protected void applyImpl(ResourceLocation variantId, RegistryAccess ra, BulletSnapshot snapshot) {
        Optional<VariantDefinition> def = lookupVariant(ra, variantId);
        if (def.isEmpty()) {
            ModularShoot.LOGGER.warn("Variant {} not found in registry; skipping apply.", variantId);
            return;
        }
        VariantDefinition variant = def.get();
        variant.traits().forEach(snapshot::setTrait);       // 合并语义：未声明键保留
        variant.stats().forEach(snapshot::setStat);         // 只覆盖声明键（设计决策 2）
        variant.damageType().ifPresent(id -> applyDamageType(variantId, id, ra, snapshot));
        variant.bulletStyleOverride().ifPresent(snapshot::setVariantStyleOverride);
    }

    /**
     * Instance implementation backing {@link #rollAndApply}; identical
     * semantics, dispatched through the instance so the seams are honoured.
     */
    protected void rollAndApplyImpl(
            ServerPlayer player, BulletSnapshot snapshot, GunData gunData, GunDefinition gunDef) {
        RegistryAccess ra = player.registryAccess();
        rollImpl(ra, player.level().getRandom(), gunDef, gunData)
                .ifPresent(id -> applyImpl(id, ra, snapshot));
    }

    /**
     * Instance implementation backing {@link #previewPool}; identical
     * semantics, dispatched through the instance so the {@link #lookupVariant}
     * seam is honoured.
     */
    protected List<PoolEntry> previewPoolImpl(
            RegistryAccess ra, GunDefinition gunDef, GunData gunData) {
        PoolBuild build = buildPool(ra, gunDef, gunData);
        List<PoolEntry> entries = new ArrayList<>(
                build.candidates().size() + (build.normalFallback() ? 1 : 0));
        for (WeightedCandidate c : build.candidates()) {
            entries.add(new PoolEntry(c.id(), c.weight(), false));
        }
        if (build.normalFallback()) {
            entries.add(new PoolEntry(null, NORMAL_FALLBACK_WEIGHT, true));
        }
        return entries;
    }

    /**
     * Resolves a variant-declared damage type id to its holder and overwrites
     * the snapshot's damage type (变体优先, 规格 §6.4). An unregistered id is
     * skipped with a WARN.
     */
    private void applyDamageType(
            ResourceLocation variantId, ResourceLocation damageTypeId,
            RegistryAccess ra, BulletSnapshot snapshot) {
        ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, damageTypeId);
        Optional<Holder.Reference<DamageType>> holder =
                ra.registryOrThrow(Registries.DAMAGE_TYPE).getHolder(key);
        if (holder.isPresent()) {
            snapshot.setDamageType(holder.get());
        } else {
            ModularShoot.LOGGER.warn(
                    "Variant {} damage_type {} not registered; skipping.", variantId, damageTypeId);
        }
    }

    /**
     * Assembles the per-shot pool (规格 §6.2 来源表): gun {@code variants}
     * first, then every valid installed plugin's {@code adds_variants}
     * (missing definitions skipped, mirroring
     * {@code AttributeModifierService.addPluginModifiers}), summing shared
     * ids via {@code merge(id, v, Double::sum)}. Variant ids contributed
     * only via {@link VariantContributorRegistry#collect()} fall back to the
     * variant's {@code base_weight}; a missing variant definition is skipped
     * with a WARN.
     *
     * @return a {@link LinkedHashMap} (declaration order) of variant id →
     *         base weight
     */
    private Map<ResourceLocation, Double> assemble(
            RegistryAccess ra, GunDefinition gunDef, GunData gunData,
            Map<ResourceLocation, List<AttributeModifier>> contribMods) {
        Map<ResourceLocation, Double> pool = new LinkedHashMap<>(gunDef.variants());
        List<PluginInstance> validPlugins =
                PluginDegradationHandler.filterValidPlugins(gunData.installedPlugins(), ra);
        for (PluginInstance instance : validPlugins) {
            Optional<PluginDefinition> pluginDef = PluginRegistry.getPlugin(ra, instance.pluginId());
            if (pluginDef.isEmpty()) {
                continue;
            }
            pluginDef.get().addsVariants().forEach((id, v) -> pool.merge(id, v, Double::sum));
        }
        for (ResourceLocation id : contribMods.keySet()) {
            if (pool.containsKey(id)) {
                continue;   // 已由枪械/插件声明，声明权重权威，无需 base_weight 兜底
            }
            Optional<VariantDefinition> def = lookupVariant(ra, id);
            if (def.isEmpty()) {
                ModularShoot.LOGGER.warn(
                        "Variant {} contributed but not registered; skipping.", id);
                continue;
            }
            pool.put(id, def.get().baseWeight());
        }
        return pool;
    }

    /** 预计算好的 roll 候选：id + 最终权重（非正权重已排除）。 */
    private record WeightedCandidate(ResourceLocation id, double weight) {
    }

    /**
     * One candidate of the per-shot pool preview (规格 §6.4，供
     * {@code /modularshoot variants} 调试命令展示).
     *
     * @param variantId       the variant id; {@code null} for the implicit
     *                        normal-bullet fallback entry
     * @param finalWeight     the candidate's final weight (positive)
     * @param normalFallback  whether this entry is the default normal-bullet
     *                        fallback (gun declared no {@code variants})
     */
    public record PoolEntry(
            @Nullable ResourceLocation variantId,
            double finalWeight,
            boolean normalFallback) {
    }

    /** Build result shared by {@link #rollImpl} and {@link #previewPoolImpl}. */
    private record PoolBuild(
            List<WeightedCandidate> candidates,
            double total,
            boolean normalFallback) {
    }
}
