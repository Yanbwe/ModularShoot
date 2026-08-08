package org.yanbwe.modularshoot.client.render;

import org.yanbwe.modularshoot.network.ShootAnimSyncService;

/**
 * Pure per-shot recoil-kick curve for the first-person gun render
 * (设计文档 §第一人称射击后坐).
 *
 * <p>When a shot fires, the held gun visibly <em>kicks</em>: it pushes back
 * into the screen and its muzzle rises, then settles within a few ticks.
 * The displacement is a pure function of the player's per-shot animation
 * timer — the same {@code shootAnimTimer} that drives the third-person arm
 * recoil pose and the {@code per_shot} shoot-texture mode — so all three
 * feedback channels pulse with the exact same cadence on every accepted
 * shot (设计文档 §与射击节奏的一致性).</p>
 *
 * <p><b>Curve:</b> {@code t = clamp(timer / SHOOT_ANIM_PEAK, 0, 1)} is
 * passed through {@code kick = t * t}. The shot tick resets the timer to the
 * peak ({@code t = 1}, maximal displacement immediately) and the quadratic
 * settles fast: 64% of the displacement is gone after the first two decay
 * ticks, which reads as a crisp, decisive pulse rather than a slow settle.
 * At high fire rates the timer resets before fully decaying, producing a
 * continuous short vibration — the same behaviour as the third-person pose.</p>
 *
 * <p><b>Transform axes</b> (in the item-centred pose received by
 * {@code renderByItem}, after the model's {@code firstperson_*} display
 * transform has been applied): the flat gun texture faces the camera, so the
 * pose frame's <em>+X</em> points into the screen (away from the viewer) and
 * its <em>+Z</em> lies along the screen's left-right axis. The kick therefore
 * translates along +X (push back) and rotates around +Z (muzzle rise toward
 * the viewer) — see {@link GunItemRenderer} for the application site.</p>
 *
 * <p>The mapping is a pure mathematical curve — no Minecraft state — so it
 * is unit-tested directly (设计文档 §渲染流程). This class is not
 * instantiable.</p>
 *
 * @see GunItemRenderer
 * @see org.yanbwe.modularshoot.client.PlayerShootStateManager
 * @see ShootAnimSyncService#SHOOT_ANIM_PEAK
 */
public final class FirstPersonRecoilKick {

    private FirstPersonRecoilKick() {
    }

    /** 后坐位移上限（格）：开火瞬间枪身沿 +X（屏幕内，远离观察者）后收的方块数。 */
    public static final float MAX_PUSH_BLOCKS = 0.2F;

    /** 枪口上抬角度上限（度）：开火瞬间绕 +Z（屏幕左右轴）旋转的角度，正值为枪口朝观察者抬起。 */
    public static final float MAX_RISE_DEGREES = 8.0F;

    /**
     * Immutable per-shot kick displacement, in the item-centred pose frame.
     *
     * @param pushBlocks  translation along the pose frame's +X axis (into the
     *                    screen, away from the viewer), in blocks; the shot
     *                    tick carries the maximum value, decaying to {@code 0}
     * @param riseDegrees rotation around the pose frame's +Z axis (the
     *                    screen's left-right axis), in degrees; positive
     *                    values tip the gun's top toward the viewer (muzzle
     *                    rise), decaying to {@code 0}
     */
    public record Kick(float pushBlocks, float riseDegrees) {

        /** Identity kick — no displacement at all. */
        public static final Kick NONE = new Kick(0.0F, 0.0F);

        /** {@return whether this kick displaces the gun at all} */
        public boolean isActive() {
            return pushBlocks != 0.0F || riseDegrees != 0.0F;
        }
    }

    /**
     * Computes the kick displacement for the given shoot-animation timer.
     *
     * <p>{@code t = clamp(timer / SHOOT_ANIM_PEAK, 0, 1)} passed through
     * {@code kick = t * t}: the shot tick ({@code timer = PEAK}) yields the
     * full displacement, and the quadratic decay settles within a few ticks.
     * A non-positive timer (or any value clamping to {@code 0}) yields
     * {@link Kick#NONE}.</p>
     *
     * @param shootAnimTimer the player's current shoot-animation timer (in
     *                       ticks, as maintained by
     *                       {@code PlayerShootStateManager}); non-positive
     *                       values mean "no shot in flight"
     * @return the kick displacement, never {@code null}
     */
    public static Kick compute(float shootAnimTimer) {
        float t = (float) Math.min(1.0, Math.max(0.0, shootAnimTimer / ShootAnimSyncService.SHOOT_ANIM_PEAK));
        if (t <= 0.0F) {
            return Kick.NONE;
        }
        float kick = t * t;
        return new Kick(kick * MAX_PUSH_BLOCKS, kick * MAX_RISE_DEGREES);
    }
}
