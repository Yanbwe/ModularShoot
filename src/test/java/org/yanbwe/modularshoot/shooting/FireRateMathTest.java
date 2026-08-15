package org.yanbwe.modularshoot.shooting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the shared fire-rate interval maths (计划 §阶段 6 / 任务 6.4).
 *
 * <p>{@link FireRateMath#computeInterval(double)} is the single source of
 * truth for turning a {@code fire_rate} attribute value (shots/second) into a
 * tick interval. It is shared by the server-authoritative
 * {@link FireRateController} and the client-side {@code ClientFireRatePredictor}
 * so the visual prediction cadence always matches the server's actual cadence
 * with exactly one formula to maintain.</p>
 */
class FireRateMathTest {

    // --- Boundary / formula coverage --------------------------------------

    @Test
    void oneShotPerSecondIsTwentyTickInterval() {
        assertEquals(20, FireRateMath.computeInterval(1.0));
    }

    @Test
    void tenShotsPerSecondIsTwoTickInterval() {
        assertEquals(2, FireRateMath.computeInterval(10.0));
    }

    @Test
    void twentyShotsPerSecondIsOneTickInterval() {
        assertEquals(1, FireRateMath.computeInterval(20.0));
    }

    @Test
    void fractionalIntervalRoundsToNearestTick() {
        // 20 / 7 ≈ 2.857 → round = 3
        assertEquals(3, FireRateMath.computeInterval(7.0));
        // 20 / 8 = 2.5 → round = 3 (ties round half away from zero via Math.round)
        assertEquals(3, FireRateMath.computeInterval(8.0));
    }

    @Test
    void intervalNeverBelowOneTick() {
        // Very high fire rates must still yield at least a 1-tick interval.
        assertEquals(1, FireRateMath.computeInterval(40.0));
        assertEquals(1, FireRateMath.computeInterval(1024.0));
        // fire_rate slightly above 20 still clamps to the 1-tick floor.
        assertEquals(1, FireRateMath.computeInterval(20.4));
    }

    // --- Both ends use the shared formula ---------------------------------

    /**
     * Architectural guard (任务 6.4): the server fire-rate gate and the client
     * fire-rate predictor must both delegate to the single shared
     * {@link FireRateMath#computeInterval} implementation, so the duplicated
     * formula cannot silently drift apart again. A duplicated formula in
     * either file fails this guard.
     */
    @Test
    void serverAndClientDelegateToSharedFireRateMath() throws IOException {
        Path root = resolveProjectRoot();
        List<String> offenders = new ArrayList<>();

        checkDelegates(root,
                "src/main/java/org/yanbwe/modularshoot/shooting/FireRateController.java",
                offenders);
        checkDelegates(root,
                "src/main/java/org/yanbwe/modularshoot/client/ClientFireRatePredictor.java",
                offenders);

        if (!offenders.isEmpty()) {
            fail("server and client must use the shared FireRateMath.computeInterval "
                    + "instead of a duplicated formula; offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    private static void checkDelegates(Path root, String rel, List<String> offenders)
            throws IOException {
        Path file = root.resolve(rel);
        if (!Files.isRegularFile(file)) {
            offenders.add(rel + " (missing)");
            return;
        }
        boolean delegates = Files.lines(file, StandardCharsets.UTF_8)
                .anyMatch(line -> line.contains("FireRateMath.computeInterval"));
        if (!delegates) {
            offenders.add(rel + " does not reference FireRateMath.computeInterval");
        }
    }

    private static Path resolveProjectRoot() {
        Path start = Paths.get("").toAbsolutePath().normalize();
        Path dir = start;
        for (int i = 0; i < 10 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve(
                    "src/main/java/org/yanbwe/modularshoot/shooting/FireRateController.java"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate the ModularShoot module root "
                + "from '" + start + "'");
    }
}
