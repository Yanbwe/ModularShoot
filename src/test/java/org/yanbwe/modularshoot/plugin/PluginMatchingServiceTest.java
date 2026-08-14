package org.yanbwe.modularshoot.plugin;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PluginMatchingService#selectPluginType} with the
 * preferred-type hint (设计草案 框架改进-插件安装优先种类-方案 §三).
 *
 * <p>The pure function is exercised headless over hand-built
 * {@link PluginTypeDefinition}s — a plain record, so no registry bootstrap
 * is needed. The {@link Random} is injected per call, keeping tie-break
 * behaviour deterministic; a throwing {@link Random} proves the hint-hit
 * path never consults randomness.</p>
 */
class PluginMatchingServiceTest {

    private static final ResourceLocation COMBAT = ResourceLocation.parse("modularshoot:combat");
    private static final ResourceLocation BARREL = ResourceLocation.parse("modularshoot:barrel");
    private static final ResourceLocation ACCESSORY = ResourceLocation.parse("modularshoot:accessory");

    /**
     * A {@link Random} that fails the test when consulted — the hint-hit path
     * must be fully deterministic and never draw from the random source.
     */
    private static final class ThrowingRandom extends Random {
        @Override
        public int nextInt(int bound) {
            throw new AssertionError("random must not be consulted when the hint hits");
        }
    }

    /** Builds a candidate carrying the given tags and priority. */
    private static PluginMatchingService.TypeMatch type(
            ResourceLocation id, List<ResourceLocation> tags, int priority) {
        return new PluginMatchingService.TypeMatch(
                id, new PluginTypeDefinition(tags, priority, Optional.empty(), Optional.empty()));
    }

    // ------------------------------------------------------------------
    // hint hit
    // ------------------------------------------------------------------

    @Test
    void hintPicksMatchingCandidateAmongMultiple() {
        // 自动算法会选 tag 更少的 barrel（1 个 tag），但 hint 钦定 combat。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT, BARREL), 10),
                type(BARREL, List.of(BARREL), 5));
        assertEquals(Optional.of(COMBAT),
                PluginMatchingService.selectPluginType(candidates, COMBAT, new ThrowingRandom()),
                "the hinted candidate is chosen even when auto-selection would pick another");
    }

    @Test
    void hintOverridesPriorityOrdering() {
        // 自动算法按 priority 降序会选 barrel(100)，hint 钦定 combat(1)。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT), 1),
                type(BARREL, List.of(BARREL), 100));
        assertEquals(Optional.of(COMBAT),
                PluginMatchingService.selectPluginType(candidates, COMBAT, new ThrowingRandom()),
                "the hint overrides the priority-descending auto-selection");
    }

    @Test
    void hintWithUniqueCandidateSelectsIt() {
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(BARREL, List.of(BARREL), 0));
        assertEquals(Optional.of(BARREL),
                PluginMatchingService.selectPluginType(candidates, BARREL, new ThrowingRandom()),
                "a single candidate matching the hint is selected");
    }

    @Test
    void hintHitNeverConsultsRandom() {
        // ThrowingRandom 已保证：hint 命中路径绝不触碰随机源。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT), 5),
                type(BARREL, List.of(BARREL), 5));
        assertEquals(Optional.of(BARREL),
                PluginMatchingService.selectPluginType(candidates, BARREL, new ThrowingRandom()),
                "a hint hit returns without any random draw");
    }

    // ------------------------------------------------------------------
    // hint miss / fallback
    // ------------------------------------------------------------------

    @Test
    void hintNotInCandidatesFallsBackToAutoSelection() {
        // hint=ACCESSORY 不在候选里 → 回退三级算法：tag 数相同 → priority 降序 → combat(10)。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT), 10),
                type(BARREL, List.of(BARREL), 5));
        assertEquals(Optional.of(COMBAT),
                PluginMatchingService.selectPluginType(candidates, ACCESSORY, new Random(42)),
                "an unmatched hint falls back to the three-level auto-selection");
    }

    @Test
    void hintMissWithTieConsultsRandom() {
        // 平局（同 tag 数、同 priority）且 hint 未命中 → 随机兜底仍生效（固定种子确定性）。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT), 7),
                type(BARREL, List.of(BARREL), 7));
        Optional<ResourceLocation> picked =
                PluginMatchingService.selectPluginType(candidates, ACCESSORY, new Random(0));
        assertTrue(picked.isPresent()
                        && (picked.get().equals(COMBAT) || picked.get().equals(BARREL)),
                "tie fallback picks one of the tied candidates");
    }

    @Test
    void nullHintBehavesAsOriginalAlgorithm() {
        // null hint 等价于原三级算法（回归对照：与无 hint 时代行为一致）。
        List<PluginMatchingService.TypeMatch> candidates = List.of(
                type(COMBAT, List.of(COMBAT), 10),
                type(BARREL, List.of(BARREL), 5));
        assertEquals(Optional.of(COMBAT),
                PluginMatchingService.selectPluginType(candidates, null, new Random(42)),
                "null hint keeps the original three-level behaviour");
    }

    // ------------------------------------------------------------------
    // empty candidates
    // ------------------------------------------------------------------

    @Test
    void hintWithEmptyCandidatesReturnsEmpty() {
        assertEquals(Optional.empty(),
                PluginMatchingService.selectPluginType(List.of(), BARREL, new ThrowingRandom()),
                "no candidates yield empty regardless of the hint");
    }
}
