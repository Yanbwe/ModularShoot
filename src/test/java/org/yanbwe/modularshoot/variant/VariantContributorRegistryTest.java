package org.yanbwe.modularshoot.variant;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link VariantContributorRegistry} (机制四 §6.2
 * 来源 3): contributors are collected per variant id, same-variant modifiers
 * merge into one list in registration order, and the empty registry yields an
 * empty map. No {@code RegistryAccess} or entity is required: modifiers are
 * plain {@link AttributeModifier} records (规格 §6.3).
 *
 * <p>The registry is a static singleton shared across tests, so
 * {@link #clearRegistry()} resets it before every case (same pattern as
 * {@code ShootEffectRegistryTest}).</p>
 */
class VariantContributorRegistryTest {

    /** Variant id contributed by both test contributors (overlap case). */
    private static final ResourceLocation SHARED = ResourceLocation.parse("modularshoot:shared");
    /** Variant id contributed only by the second test contributor. */
    private static final ResourceLocation SECOND_ONLY = ResourceLocation.parse("modularshoot:second_only");

    @BeforeEach
    void clearRegistry() {
        VariantContributorRegistry.clear();
    }

    // ------------------------------------------------------------------
    // 按变体 id 收集，同变体多修饰符按注册顺序合并
    // ------------------------------------------------------------------

    @Test
    void contributorsCollectedByVariantId() {
        AttributeModifier firstMod = mod(1.0);   // 第一个 contributor 对 SHARED 的修饰符
        AttributeModifier secondMod = mod(2.0);  // 第二个 contributor 对 SHARED 的修饰符
        AttributeModifier secondMod2 = mod(3.0); // 第二个 contributor 对 SECOND_ONLY 的修饰符
        // 第一个 contributor：贡献 1 个变体。
        VariantContributorRegistry.register(sink -> sink.add(SHARED, firstMod));
        // 第二个 contributor：贡献 2 个变体，其中一个 id 与前者重叠。
        VariantContributorRegistry.register(sink -> {
            sink.add(SHARED, secondMod);
            sink.add(SECOND_ONLY, secondMod2);
        });

        Map<ResourceLocation, List<AttributeModifier>> collected =
                VariantContributorRegistry.collect();

        assertEquals(2, collected.size(),
                "1 + 2 contributions with one overlapping id yield 2 distinct ids");
        assertEquals(List.of(firstMod, secondMod), collected.get(SHARED),
                "overlapping variant merges both modifiers in registration order (先注册的在前)");
        assertEquals(List.of(secondMod2), collected.get(SECOND_ONLY),
                "a variant contributed by a single contributor keeps exactly its one modifier");
    }

    // ------------------------------------------------------------------
    // 空注册表
    // ------------------------------------------------------------------

    @Test
    void emptyRegistryYieldsEmptyMap() {
        Map<ResourceLocation, List<AttributeModifier>> collected =
                VariantContributorRegistry.collect();

        assertTrue(collected.isEmpty(), "an empty registry collects nothing");
    }

    /** Builds an {@link AttributeModifier} with the given amount (ADD_VALUE). */
    private static AttributeModifier mod(double amount) {
        return new AttributeModifier(
                ResourceLocation.parse("modularshoot:test"),
                amount,
                AttributeModifier.Operation.ADD_VALUE);
    }
}
