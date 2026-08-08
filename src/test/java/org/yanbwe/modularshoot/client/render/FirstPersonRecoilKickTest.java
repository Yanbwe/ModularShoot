package org.yanbwe.modularshoot.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import org.yanbwe.modularshoot.network.ShootAnimSyncService;

/**
 * Unit tests for {@link FirstPersonRecoilKick#compute}: the per-shot
 * first-person recoil-kick curve. The curve is a pure function of the
 * shoot-animation timer — no Minecraft state — so it is exercised with plain
 * JUnit (设计文档 §第一人称射击后坐).
 */
class FirstPersonRecoilKickTest {

    private static final float PEAK = ShootAnimSyncService.SHOOT_ANIM_PEAK;

    private static final float EPSILON = 1e-6F;

    @Test
    void zeroTimerYieldsNoKick() {
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(0.0F);
        assertEquals(0.0F, kick.pushBlocks(), EPSILON, "no shot in flight → no push");
        assertEquals(0.0F, kick.riseDegrees(), EPSILON, "no shot in flight → no rise");
        assertFalse(kick.isActive(), "the identity kick must be inactive");
        assertSame(FirstPersonRecoilKick.Kick.NONE, kick, "timer 0 returns the shared identity instance");
    }

    @Test
    void negativeTimerYieldsNoKick() {
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(-3.0F);
        assertEquals(0.0F, kick.pushBlocks(), EPSILON, "negative timers clamp to no displacement");
        assertEquals(0.0F, kick.riseDegrees(), EPSILON);
    }

    @Test
    void shotTickYieldsFullDisplacement() {
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(PEAK);
        assertEquals(FirstPersonRecoilKick.MAX_PUSH_BLOCKS, kick.pushBlocks(), EPSILON,
                "the shot tick carries the maximum push");
        assertEquals(FirstPersonRecoilKick.MAX_RISE_DEGREES, kick.riseDegrees(), EPSILON,
                "the shot tick carries the maximum rise");
        assertTrue(kick.isActive());
    }

    @Test
    void timerBeyondPeakIsClampedToFullDisplacement() {
        // The timer never exceeds the peak in practice (it is reset to the
        // peak on a shot), but a defensive clamp must hold for robustness.
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(PEAK * 2.0F);
        assertEquals(FirstPersonRecoilKick.MAX_PUSH_BLOCKS, kick.pushBlocks(), EPSILON);
        assertEquals(FirstPersonRecoilKick.MAX_RISE_DEGREES, kick.riseDegrees(), EPSILON);
    }

    @Test
    void halfTimerIsQuarterDisplacement() {
        // t = 0.5 → kick = t² = 0.25: the quadratic curve concentrates the
        // displacement on the shot tick and settles fast (干脆利落).
        float half = PEAK / 2.0F;
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(half);
        assertEquals(0.25F * FirstPersonRecoilKick.MAX_PUSH_BLOCKS, kick.pushBlocks(), EPSILON,
                "t = 1/2 must yield a quarter of the max push, not half (quadratic curve)");
        assertEquals(0.25F * FirstPersonRecoilKick.MAX_RISE_DEGREES, kick.riseDegrees(), EPSILON,
                "t = 1/2 must yield a quarter of the max rise");
    }

    @Test
    void decayIsMonotonicAndFast() {
        // Sample the five decay ticks after a shot (timer 5 → 1): the
        // displacement must shrink strictly, and 64% must be gone after the
        // first two decay ticks.
        float previous = Float.MAX_VALUE;
        float firstTwoDecay = -1.0F;
        for (int timer = (int) PEAK; timer >= 1; timer--) {
            FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(timer);
            float magnitude = Math.abs(kick.pushBlocks());
            assertTrue(magnitude < previous,
                    "displacement must shrink as the timer decays (timer " + timer + ": " + magnitude + ")");
            previous = magnitude;
            if (timer == (int) PEAK - 2) {
                firstTwoDecay = magnitude;
            }
        }
        assertTrue(firstTwoDecay >= 0.0F && firstTwoDecay <= 0.36F * FirstPersonRecoilKick.MAX_PUSH_BLOCKS,
                "two ticks after the shot at most 36% of the max push remains (" + firstTwoDecay + ")");
    }

    @Test
    void riseIsPositiveAndPushIsPositive() {
        // Sign conventions: push along +X (into the screen, away from the
        // viewer) and a positive rotation around +Z (muzzle rise toward the
        // viewer) — see FirstPersonRecoilKick's axis documentation.
        FirstPersonRecoilKick.Kick kick = FirstPersonRecoilKick.compute(PEAK);
        assertTrue(kick.pushBlocks() > 0.0F, "the gun must push back into the screen");
        assertTrue(kick.riseDegrees() > 0.0F, "the muzzle must rise toward the viewer");
    }
}
