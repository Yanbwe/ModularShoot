package org.yanbwe.modularshoot.variant;

/**
 * Functional interface for external sources (e.g. trinkets, armor sets) that
 * contribute weight modifiers to the per-shot variant pool (机制四 §6.2
 * 来源 3).
 *
 * <p>Third-party mods register implementations via
 * {@link VariantContributorRegistry#register(VariantContributor)} to influence
 * which variant of a gun's pool gets rolled for each shot. A contributor
 * declares zero or more "variant id &rarr; weight modifier" pairs through the
 * {@link VariantContributionSink} it receives; the modifiers are merged with
 * the base weights declared by the gun definition ({@code variants}) and
 * installed plugins ({@code adds_variants}) before the roll (规格 §6.2).</p>
 *
 * <p>Modifiers reuse the vanilla {@link net.minecraft.world.entity.ai.attributes.AttributeModifier}
 * record and its {@code Operation} three-stage semantics (规格 §6.3):
 * {@code ADD_VALUE} adds a flat amount to the base weight,
 * {@code ADD_MULTIPLIED_BASE} multiplies only the base part (a "fire-bullet
 * doubling" trinket is therefore useless on guns that declare a zero base
 * weight — the result stays {@code 0}), and {@code ADD_MULTIPLIED_TOTAL}
 * scales the whole final weight.</p>
 *
 * <p>Contributions are collected by {@link VariantContributorRegistry#collect()}
 * every time the per-shot pool is assembled; they are never persisted. A
 * variant id contributed but not declared by any gun/plugin still enters the
 * pool, with the variant's own {@code base_weight} as its base weight (default
 * {@code 0.0}); ids already declared by the gun or an installed plugin keep
 * their declared weight as authoritative (任务说明设计决策 1).</p>
 *
 * <p>The framework registers zero contributors by default. Implementations
 * should be pure and side-effect free: they only declare modifiers through the
 * sink and must not mutate any global state.</p>
 */
@FunctionalInterface
public interface VariantContributor {

    /**
     * Declares this source's weight modifiers for the current shot's variant
     * pool.
     *
     * @param sink the receiving end for "variant id &rarr; weight modifier"
     *             declarations; must not be {@code null}
     */
    void contribute(VariantContributionSink sink);
}
