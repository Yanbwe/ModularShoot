package org.yanbwe.modularshoot.mixin.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.yanbwe.modularshoot.ModularShootAPI;

/**
 * Client-side Mixin that suppresses the vanilla arm-swing animation when the
 * player is holding a framework gun in their main hand.
 *
 * <p>The framework's {@code LeftClickInterceptHandler} already cancels the
 * vanilla attack/block-break events, but the arm-swing animation is triggered
 * <em>before</em> those events in the client-side input handling
 * ({@code Minecraft.startAttack()}), so the player still sees their arm flail
 * uselessly every time they hold the attack key. This Mixin intercepts the
 * {@link LivingEntity#swing(InteractionHand)} call — the method responsible
 * for starting the swing animation — and cancels it when the entity is a
 * player holding a gun.</p>
 *
 * <p><b>Client-only.</b> Registered in the {@code "client"} section of
 * {@code modularshoot.mixins.json} and guarded with
 * {@code level().isClientSide()}. The swing on the server side is left
 * untouched so other clients still see the vanilla arm motion for remote
 * players (the Mixin is anyway only loaded on the physical client, but the
 * guard keeps the intent explicit).</p>
 *
 * <p><b>Degradation:</b> if this Mixin fails to load the player simply sees
 * the vanilla arm swing; shooting logic is completely unaffected.</p>
 *
 * @see org.yanbwe.modularshoot.shooting.LeftClickInterceptHandler
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {

    /**
     * Cancels the arm-swing animation for players holding a gun in their main
     * hand, on the client side only.
     *
     * @param hand the hand that would swing (unused)
     * @param ci   the mixin callback info (used to cancel)
     */
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
    private void modularshoot$cancelSwingForGun(InteractionHand hand, CallbackInfo ci) {
        // Fast-path: only consider players on the client side.
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player) || !player.level().isClientSide()) {
            return;
        }
        if (ModularShootAPI.isGun(player.getMainHandItem())) {
            ci.cancel();
        }
    }
}
