package org.yanbwe.modularshoot.mixin.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.client.PlayerShootStateManager;
import org.yanbwe.modularshoot.network.ShootAnimSyncService;

/**
 * Client-only Mixin that injects the third-person shoot animation into
 * {@link HumanoidModel#setupAnim} (设计文档 §第三人称射击动画实现).
 *
 * <p>The framework's shooting is triggered by the attack key (left-click)
 * rather than vanilla's {@code useItem} state machine, so the vanilla
 * crossbow charge/hold animation never plays for guns. This Mixin bridges
 * that gap by reading the per-player {@code shootAnimTimer} (maintained by
 * {@link PlayerShootStateManager}) <em>after</em> the vanilla animation
 * calculations run, and applying a short recoil pulse to both arms while
 * the timer is positive.</p>
 *
 * <p><b>Injection point:</b> {@code TAIL} of {@code HumanoidModel.setupAnim}.
 * This fires after every vanilla arm-pose calculation. For the local player
 * the model is only rendered in third-person view (first-person uses
 * {@code ItemInHandRenderer}, a completely separate path), so this Mixin
 * naturally has no first-person effect (设计文档 §渲染视角).</p>
 *
 * <p><b>Sleeve sync:</b> {@code PlayerModel.setupAnim} overrides this method,
 * calls {@code super.setupAnim(...)} first, then copies the arm rotations to
 * the sleeve parts ({@code leftSleeve.copyFrom(leftArm)} etc.). Because this
 * injection runs at the tail of the super call &mdash; <em>before</em>
 * {@code PlayerModel}'s copyFrom lines execute &mdash; the sleeves correctly
 * inherit the modified arm rotations.</p>
 *
 * <p><b>Pose curve:</b> a short recoil pulse rather than a held draw. With
 * {@code t = timer / SHOOT_ANIM_PEAK} the displacement is maximal on the shot
 * tick ({@code t = 1}, the timer peaks when a shot fires) and decays to 0 as
 * the timer runs out; {@code kick = t * t} makes it settle within a few ticks.
 * The primary arm (holding the gun) keeps its fixed gun-hold pose with an
 * upward muzzle kick added at the shot tick; the secondary arm jerks slightly
 * and relaxes back to its natural pose. The old crossbow-draw interpolation
 * is deliberately removed: at fire rates of 4/s or more every shot resets the
 * timer, so a draw-based curve froze the arms in the fully-drawn pose forever
 * (装填语义, not firing semantics).</p>
 *
 * <p><b>Degradation:</b> if this Mixin fails to load (e.g. a signature
 * mismatch with another mod), the animation simply does not play and the
 * player keeps the vanilla arm pose &mdash; shooting logic is unaffected
 * (设计文档 §降级).</p>
 *
 * @see PlayerShootStateManager
 * @see ShootAnimSyncService#SHOOT_ANIM_PEAK
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    /** 持枪臂（primary）无后坐时的基础 xRot：横枪姿态（沿用原弩持枪关键帧值）。 */
    private static final float SHOOT_HOLD_ARM_XROT = -0.97079635f;

    /** 开火瞬间枪口上抬幅度：叠加在基础 xRot 上（xRot 更负 = 手臂上扬），随 kick 衰减回位。 */
    private static final float SHOOT_RECOIL_ARM_XROT_KICK = 0.45f;

    /** 辅助臂（secondary）后收脉冲的 yRot 幅度（开火瞬间微收的扭转，随 kick 衰减回自然姿态）。 */
    private static final float SHOOT_RECOIL_SECONDARY_YROT = 0.35f;

    /** 辅助臂（secondary）后收脉冲的 xRot 幅度（开火瞬间随枪微抬，随 kick 衰减回自然姿态）。 */
    private static final float SHOOT_RECOIL_SECONDARY_XROT = -0.35f;

    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart leftArm;

    /**
     * Runs at the tail of {@code HumanoidModel.setupAnim}: when the rendered
     * entity is a player with a positive shoot-animation timer <em>and</em>
     * holding a framework gun in their main hand, applies the short recoil
     * pulse to both arms. Otherwise does nothing.
     *
     * <p>The main-hand gun guard (W22) ensures the recoil pose is only
     * applied while the player is actually holding a gun. Without this guard,
     * switching to a non-gun item while the timer is still decaying would
     * leave the recoil pose applied to a non-shooting animation, which looks
     * broken.</p>
     *
     * @param entity          the living entity being animated (erased generic {@code T})
     * @param limbSwing       vanilla limb-swing phase (unused)
     * @param limbSwingAmount vanilla limb-swing amount (unused)
     * @param ageInTicks      vanilla age in ticks (unused)
     * @param netHeadYaw      vanilla net head yaw (unused)
     * @param headPitch       vanilla head pitch (unused)
     * @param ci              the mixin callback info
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void modularshoot$applyShootAnim(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                             float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof Player player)) {
            return;
        }
        float timer = PlayerShootStateManager.getInstance().getAnimTimer(player.getUUID());
        if (timer <= 0.0f) {
            return; // timer == 0: do not interfere, keep vanilla animation
        }
        if (!ModularShootAPI.isGun(player.getMainHandItem())) {
            return; // main hand is not a gun: do not apply shoot recoil pose
        }
        applyShootRecoilPose(player, timer);
    }

    /**
     * Applies a short recoil pulse to both arms, driven by
     * {@code t = timer / SHOOT_ANIM_PEAK} ({@code 1} = shot tick, {@code 0} =
     * animation end) with {@code kick = t * t} for a fast decay &mdash; the
     * displacement is maximal on the shot tick and settles within a few ticks.
     *
     * <p>The primary arm (the player's main hand, holding the gun) keeps the
     * fixed gun-hold pose (yRot {@code ±0.8}, xRot {@value #SHOOT_HOLD_ARM_XROT})
     * with an upward muzzle kick added to xRot at the shot tick: the muzzle
     * rises at the moment of firing and settles back to the hold as
     * {@code kick} decays (开火瞬间枪口上抬、随 kick 衰减回位).</p>
     *
     * <p>The secondary arm (the off hand) jerks slightly at the shot tick and
     * relaxes back to its natural pose ({@code 0}) as {@code kick} decays &mdash;
     * a short jerk instead of the old crossbow-draw interpolation, which at
     * high fire rates froze the arms in the fully-drawn pose forever.</p>
     *
     * @param player the player whose arms to pose
     * @param timer  the player's current shoot-animation timer (in ticks, {@code > 0})
     */
    private void applyShootRecoilPose(Player player, float timer) {
        float t = Mth.clamp(timer / ShootAnimSyncService.SHOOT_ANIM_PEAK, 0.0f, 1.0f); // 1=开火瞬间（timer=峰值）→ 0=动画结束
        float kick = t * t; // 快速衰减：前几 tick 位移大、随后迅速回位
        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        ModelPart primary = rightHanded ? this.rightArm : this.leftArm;
        ModelPart secondary = rightHanded ? this.leftArm : this.rightArm;
        // 持枪手：横枪基值（yRot ±0.8）不变；xRot 在基值上叠后坐脉冲（更负 = 枪口上抬），随 kick 衰减回位
        primary.yRot = rightHanded ? -0.8f : 0.8f;
        primary.xRot = SHOOT_HOLD_ARM_XROT - kick * SHOOT_RECOIL_ARM_XROT_KICK;
        // 辅助手：开火瞬间微收（后收脉冲），随 kick 衰减回自然姿态（0）
        secondary.yRot = Mth.lerp(kick, 0.0f, SHOOT_RECOIL_SECONDARY_YROT) * (rightHanded ? 1 : -1);
        secondary.xRot = Mth.lerp(kick, 0.0f, SHOOT_RECOIL_SECONDARY_XROT);
    }
}
