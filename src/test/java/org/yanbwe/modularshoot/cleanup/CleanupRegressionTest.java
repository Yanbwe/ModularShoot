package org.yanbwe.modularshoot.cleanup;

import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.client.tooltip.TooltipUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for 阶段 7 / 任务 7.1 cleanup &amp; robustness fixes.
 *
 * <p>These pin the behaviour that the low-priority cleanups must preserve or
 * newly guarantee — keeping them small and free of heavy vanilla harnesses
 * wherever possible. Only items with a clean, dependency-free seam are
 * covered here; the rest are verified by the full-suite build (compilation
 * exercises the signature / wiring changes). The network-layer render-mode
 * defence is covered by {@code BulletS2CPacketCodecTest} (same package as
 * the codec, where the package-private helper is accessible).</p>
 *
 * <ul>
 *   <li><b>TooltipUtils numeric formatting (任务 7.1 / item 2)</b> — the
 *       {@link ThreadLocal} {@code DecimalFormat} refactor must not change
 *       the formatted output.</li>
 * </ul>
 */
class CleanupRegressionTest {

    // ------------------------------------------------------------------
    // Item 2 — TooltipUtils ThreadLocal DecimalFormat output unchanged
    // ------------------------------------------------------------------

    @Test
    void formatValueTrimsTrailingZerosAsBefore() {
        assertEquals("2", TooltipUtils.formatValue(2.0),
                "integer renders without decimal point");
        assertEquals("0.5", TooltipUtils.formatValue(0.5),
                "fraction keeps up to two decimals");
        assertEquals("3.14", TooltipUtils.formatValue(3.14),
                "two-decimal value preserved");
        assertEquals("0", TooltipUtils.formatValue(0.0),
                "zero renders as 0");
    }

    @Test
    void formatValueIsStableAcrossRepeatedCalls() {
        // Regression against the shared-format being mutated by concurrent use:
        // repeated calls must keep producing identical, deterministic output.
        for (int i = 0; i < 10; i++) {
            assertEquals("7.25", TooltipUtils.formatValue(7.25), "repeat call stable");
        }
    }
}
