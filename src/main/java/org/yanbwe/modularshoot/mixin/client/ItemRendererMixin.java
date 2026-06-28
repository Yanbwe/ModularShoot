package org.yanbwe.modularshoot.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.UUID;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.yanbwe.modularshoot.client.render.GunItemRenderer;

/**
 * Client-only Mixin on {@link ItemRenderer#renderStatic} that bridges the
 * vanilla API gap where {@code renderByItem} does not receive the holding
 * entity (设计文档 §渲染器, §第三人称射击纹理).
 *
 * <p>{@link GunItemRenderer#renderByItem} resolves the shoot texture per
 * holding player, but the vanilla {@code renderByItem} signature carries no
 * {@code LivingEntity} parameter. For the local player (first-person) the
 * renderer can fall back to {@code Minecraft.getInstance().player}, but for
 * <em>remote</em> players rendered in third-person that fallback resolves the
 * wrong uuid &mdash; the local player's &mdash; causing
 * {@code per_shot}/{@code while_firing} texture switching to never trigger for
 * remote players.</p>
 *
 * <p>This Mixin fixes that by bracketing the
 * {@code renderStatic(LivingEntity, ...)} call: at {@code HEAD} it records the
 * rendered entity's uuid into the {@link GunItemRenderer#setRenderingPlayer}
 * ThreadLocal, and at {@code TAIL} it clears it via
 * {@link GunItemRenderer#clearRenderingPlayer}. Because
 * {@code renderStatic} &rarr; {@code render} &rarr; {@code renderByItem} is a
 * synchronous call chain, the ThreadLocal is populated before
 * {@code renderByItem} reads it and torn down immediately afterwards.</p>
 *
 * <h2>Target method selection</h2>
 * <p>{@link ItemRenderer} declares two {@code renderStatic} overloads:</p>
 * <ul>
 *   <li>{@code renderStatic(ItemStack, ItemDisplayContext, int, int, PoseStack,
 *       MultiBufferSource, Level, int)} &mdash; delegates internally with
 *       {@code livingEntity = null}.</li>
 *   <li>{@code renderStatic(LivingEntity, ItemStack, ItemDisplayContext,
 *       boolean, PoseStack, MultiBufferSource, Level, int, int, int)} &mdash;
 *       the real implementation, used for entity-held-item rendering.</li>
 * </ul>
 * <p>Injecting into the second overload covers both call sites: external
 * callers of the second overload (third-person entity rendering with a real
 * {@code LivingEntity}) and the first overload's internal delegation (GUI /
 * ground / frame rendering with {@code null}). When {@code livingEntity} is
 * {@code null} or not a {@link Player}, {@code null} is recorded so that
 * {@link GunItemRenderer} falls back to the local player (first-person) or
 * {@code NULL_UUID} (menu) as appropriate.</p>
 *
 * <h2>Degradation (W20)</h2>
 * <p>Both injections use {@code require = 0}: if a future Minecraft version
 * changes the {@code renderStatic} signature, the injections are silently
 * skipped rather than crashing the game. The worst case is that remote-player
 * shoot-texture switching stops working (reverting to the pre-fix behavior),
 * while shooting logic and the local player's texture switching remain
 * unaffected (设计文档 §降级).</p>
 *
 * @see GunItemRenderer#setRenderingPlayer
 * @see GunItemRenderer#clearRenderingPlayer
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    /**
     * Records the rendered entity's uuid before the vanilla pipeline runs.
     *
     * <p>When the entity is a {@link Player}, its uuid is recorded so that
     * {@link GunItemRenderer} resolves the correct shoot texture for that
     * specific player (fixing remote-player third-person rendering). When the
     * entity is {@code null} or not a player (e.g. GUI, ground, frame), the
     * context is set to {@code null} so the renderer's fallback logic
     * applies.</p>
     *
     * @param livingEntity      the entity whose item is being rendered (nullable)
     * @param itemStack         the item stack (unused)
     * @param itemDisplayContext the display context (unused)
     * @param bl                left-handed flag (unused)
     * @param poseStack         the pose stack (unused)
     * @param multiBufferSource the buffer source (unused)
     * @param level             the level (unused)
     * @param i                 packed light (unused)
     * @param j                 packed overlay (unused)
     * @param k                 seed (unused)
     * @param ci                the mixin callback info
     */
    @Inject(
            method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("HEAD"),
            require = 0
    )
    private void modularshoot$setRenderingPlayer(
            @Nullable LivingEntity livingEntity,
            ItemStack itemStack,
            ItemDisplayContext itemDisplayContext,
            boolean bl,
            PoseStack poseStack,
            MultiBufferSource multiBufferSource,
            @Nullable Level level,
            int i,
            int j,
            int k,
            CallbackInfo ci) {
        UUID uuid = (livingEntity instanceof Player player) ? player.getUUID() : null;
        GunItemRenderer.setRenderingPlayer(uuid);
    }

    /**
     * Clears the recorded uuid after the vanilla pipeline finishes.
     *
     * <p>{@code TAIL} fires before every {@code return} in the target method,
     * including the early return when {@code itemStack.isEmpty()}, so the
     * context is always torn down and never leaks into subsequent renders.</p>
     *
     * @param livingEntity      the entity whose item was rendered (unused)
     * @param itemStack         the item stack (unused)
     * @param itemDisplayContext the display context (unused)
     * @param bl                left-handed flag (unused)
     * @param poseStack         the pose stack (unused)
     * @param multiBufferSource the buffer source (unused)
     * @param level             the level (unused)
     * @param i                 packed light (unused)
     * @param j                 packed overlay (unused)
     * @param k                 seed (unused)
     * @param ci                the mixin callback info
     */
    @Inject(
            method = "renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V",
            at = @At("TAIL"),
            require = 0
    )
    private void modularshoot$clearRenderingPlayer(
            @Nullable LivingEntity livingEntity,
            ItemStack itemStack,
            ItemDisplayContext itemDisplayContext,
            boolean bl,
            PoseStack poseStack,
            MultiBufferSource multiBufferSource,
            @Nullable Level level,
            int i,
            int j,
            int k,
            CallbackInfo ci) {
        GunItemRenderer.clearRenderingPlayer();
    }
}
