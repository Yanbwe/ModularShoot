package org.yanbwe.modularshoot.mixin.client;

import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Client-side Mixin that clamps the vanilla attack cooldown scale to full
 * (always returns {@code 1.0}) while the player is holding a framework gun
 * in their main hand.
 *
 * <p>The framework already cancels the vanilla attack/block-break events
 * ({@code LeftClickInterceptHandler}) and suppresses the arm-swing animation
 * ({@code LivingEntitySwingMixin}), but the attack cooldown bar read by
 * {@code Gui.renderAttackIndicator} and {@code ItemInHandRenderer} still
 * drains and refills with every click cycle &mdash; a periodic melee cooldown
 * appears under the crosshair that conveys the wrong semantics (the click is
 * firing a gun, not swinging a melee weapon).</p>
 *
 * <p><b>Display-side clamp only.</b> This Mixin only forces the value on the
 * physical client (guarded with {@code level().isClientSide()}, the same
 * pattern as {@code LivingEntitySwingMixin} &mdash; no client classes are
 * referenced, so the class is safe to load on a dedicated server).</p>
 *
 * <p><b>No server-side side effect:</b> the server also reads this value
 * inside {@code Player.attack()} to scale melee damage, but that code path
 * never executes while a gun is held &mdash; the attack events are already
 * cancelled by {@code LeftClickInterceptHandler} &mdash; and when
 * {@code level().isClientSide()} is {@code false} this injection does
 * nothing, so server logic is untouched.</p>
 *
 * <p><b>Degradation:</b> if this Mixin fails to load the player simply sees
 * the vanilla cooldown bar drain cycles; shooting logic is completely
 * unaffected.</p>
 *
 * @see org.yanbwe.modularshoot.shooting.LeftClickInterceptHandler
 */
@Mixin(Player.class)
public abstract class PlayerAttackStrengthMixin {

    /**
     * Forces the attack strength scale to full for players holding a gun in
     * their main hand, on the client side only.
     *
     * <p>持枪时攻击冷却条钳制为满值（准星下沿不再出现周期性近战冷却条）；
     * 服务端调用点（{@code Player.attack()} 内乘算）因攻击事件已被
     * {@code LeftClickInterceptHandler} 取消而不执行，且非客户端时本注入
     * 直接放行，不影响服务端逻辑。</p>
     *
     * @param partialTick the vanilla partial-tick argument (unused)
     * @param cir         the mixin callback info (used to override the return value)
     */
    @Inject(method = "getAttackStrengthScale(F)F", at = @At("HEAD"), cancellable = true)
    private void modularshoot$clampAttackStrengthForGun(float partialTick, CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (self.level().isClientSide() && ModularShootAPI.isGun(self.getMainHandItem(), self.registryAccess())) {
            cir.setReturnValue(1.0f);
        }
    }
}
