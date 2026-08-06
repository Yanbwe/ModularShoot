package org.yanbwe.modularshoot.variant;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * Receiving end of a {@link VariantContributor}'s weight declarations: the
 * contributor declares "variant id &rarr; weight modifier" pairs (机制四 §6.2
 * 来源 3).
 *
 * <p>The sink is only valid for the duration of the contributing call; the
 * registry collects the declared modifiers into a per-shot pool that is
 * assembled anew for every shot and never persisted (规格 §6.2).</p>
 */
@FunctionalInterface
public interface VariantContributionSink {

    /**
     * Declares one weight modifier for a variant id.
     *
     * @param variantId the variant id the modifier applies to; must not be
     *                  {@code null}
     * @param modifier  the weight modifier (vanilla {@link AttributeModifier}
     *                  record, 规格 §6.3); must not be {@code null}
     */
    void add(ResourceLocation variantId, AttributeModifier modifier);
}
