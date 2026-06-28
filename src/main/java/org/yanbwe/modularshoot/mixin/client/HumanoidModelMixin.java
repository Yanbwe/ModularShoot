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
 * calculations run, and forcibly overriding both arms' rotation to the
 * vanilla crossbow-draw pose while the timer is positive.</p>
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
 * <p><b>Angle source:</b> the keyframe values replicate
 * {@code AnimationUtils.animateCrossbowCharge} from vanilla, interpolated by
 * {@code progress = timer / SHOOT_ANIM_PEAK}. The primary arm (holding the
 * gun) holds a fixed pose; the secondary arm (pulling the string) interpolates
 * from a partial draw to a full draw as {@code progress} approaches 1.</p>
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

    /** Vanilla crossbow-charge primary-arm x-rotation keyframe (from {@code AnimationUtils.animateCrossbowCharge}). */
    private static final float CROSSBOW_CHARGE_ARM_XROT = -0.97079635f;

    /** Vanilla crossbow-charge secondary-arm y-rotation start keyframe. */
    private static final float CROSSBOW_CHARGE_SECONDARY_YROT_START = 0.4f;

    /** Vanilla crossbow-charge secondary-arm y-rotation end keyframe (full draw). */
    private static final float CROSSBOW_CHARGE_SECONDARY_YROT_END = 0.85f;

    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart leftArm;

    /**
     * Runs at the tail of {@code HumanoidModel.setupAnim}: when the rendered
     * entity is a player with a positive shoot-animation timer <em>and</em>
     * holding a framework gun in their main hand, overrides both arms to the
     * crossbow-draw pose. Otherwise does nothing.
     *
     * <p>The main-hand gun guard (W22) ensures the crossbow pose is only
     * applied while the player is actually holding a gun. Without this guard,
     * switching to a non-gun item while the timer is still decaying would
     * leave the vanilla crossbow-draw pose applied to a non-shooting
     * animation, which looks broken.</p>
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
            return; // main hand is not a gun: do not apply crossbow draw pose
        }
        applyCrossbowDrawPose(player, timer);
    }

    /**
     * Forces both arms into the vanilla crossbow-charge pose, interpolated by
     * {@code progress = timer / SHOOT_ANIM_PEAK}.
     *
     * <p>The primary arm (the player's main hand, holding the gun) is set to
     * the fixed crossbow-hold keyframe. The secondary arm (the off hand,
     * conceptually pulling the string) interpolates from a partial draw
     * ({@code progress = 0}) to a full draw ({@code progress = 1}). When the
     * player just fired, {@code timer = SHOOT_ANIM_PEAK} so {@code progress = 1}
     * and the pose is fully drawn; as the timer decays the pose relaxes.</p>
     *
     * <p>Angle values replicate {@code AnimationUtils.animateCrossbowCharge}
     * from vanilla 1.21.1, with {@code progress} substituted for the vanilla
     * charge-progress {@code h}.</p>
     *
     * @param player the player whose arms to pose
     * @param timer  the player's current shoot-animation timer (in ticks, {@code > 0})
     */
    private void applyCrossbowDrawPose(Player player, float timer) {
        float progress = Mth.clamp(timer / ShootAnimSyncService.SHOOT_ANIM_PEAK, 0.0f, 1.0f);
        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        ModelPart primary = rightHanded ? this.rightArm : this.leftArm;
        ModelPart secondary = rightHanded ? this.leftArm : this.rightArm;
        // Replicates AnimationUtils.animateCrossbowCharge(rightArm, leftArm, entity, bl):
        //   primary.yRot  = bl ? -0.8F : 0.8F
        //   primary.xRot  = -0.97079635F
        //   secondary.yRot = Mth.lerp(h, 0.4F, 0.85F) * (bl ? 1 : -1)
        //   secondary.xRot = Mth.lerp(h, -0.97079635F, -PI/2)
        primary.yRot = rightHanded ? -0.8f : 0.8f;
        primary.xRot = CROSSBOW_CHARGE_ARM_XROT;
        secondary.yRot = Mth.lerp(progress, CROSSBOW_CHARGE_SECONDARY_YROT_START, CROSSBOW_CHARGE_SECONDARY_YROT_END)
                * (rightHanded ? 1 : -1);
        secondary.xRot = Mth.lerp(progress, CROSSBOW_CHARGE_ARM_XROT, (float) (-Math.PI / 2));
    }
}
