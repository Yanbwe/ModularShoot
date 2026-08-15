package org.yanbwe.modularshoot.shooting;

import java.util.Objects;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.attribute.AttributeResolver;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.network.ShootAnimSyncService;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.util.GunRecognition;

/**
 * Server-side orchestrator for
 * {@link org.yanbwe.modularshoot.network.ShootC2SPacket ShootC2SPacket}
 * handling.
 *
 * <p>Implements the server-side processing pipeline from 设计文档 §网络同步方案
 * (服务端处理), steps 1&ndash;3 plus the fire-rate gate, and delegates the
 * remaining bullet-spawn work to the shooting engine.</p>
 *
 * <p><b>Pipeline:</b></p>
 * <ol>
 *   <li>Reject the request while the player has a container open (defense
 *       in depth — the client may still emit packets while a GUI is open,
 *       and this guard also avoids setting {@code isFiring} then).</li>
 *   <li>Validate the main-hand item is a {@code modularshoot:gun}.</li>
 *   <li>Read {@link GunData} (modifier version + gun id) from the stack.</li>
 *   <li>Run the {@link ModifierVersionAntiCheat} modifier-version check.</li>
 *   <li>Run the {@link FireRateController} tick-based fire-rate gate using
 *       the player's final {@code fire_rate} attribute value.</li>
 *   <li>Delegate to the shooting engine for look-angle
 *       derivation, spread, bullet snapshot, {@code BulletManager}
 *       registration and broadcast.</li>
 * </ol>
 *
 * <p>Each step is a short-circuit guard: the first failure drops the request
 * silently (the packet is stateless, so a dropped request simply means one
 * fewer shot that tick). The class is not instantiable; all methods are
 * static.</p>
 */
public final class ShootPacketHandler {

    private ShootPacketHandler() {
    }

    /**
     * Entry point for a {@code ShootC2SPacket}: runs the full server-side
     * validation pipeline and, when every gate passes, delegates to the
     * shooting engine.
     *
     * <p>The {@code packetModifierVersion} is the version carried by the
     * client's packet; it is compared against the server's current
     * {@link GunData#modifierVersion()} by the anti-cheat step.</p>
     *
     * @param player                the shooting player; must not be {@code null}
     * @param packetModifierVersion the {@code modifierVersion} from the packet
     */
    public static void handleShootRequest(ServerPlayer player, int packetModifierVersion) {
        Objects.requireNonNull(player, "player");
        // 纵深防御：客户端 GUI 打开期间（含聊天/背包）仍可能发包——容器打开时
        // 拒绝射击且不置 isFiring（设计文档 §服务端处理）。
        if (player.hasContainerOpen()) {
            return;
        }
        // Notify the animation sync service that this player is actively shooting
        // (设计文档 §isFiring 标记维护: 收到 ShootC2SPacket 即置 true).
        ShootAnimSyncService.getInstance().onShootPacketReceived(player);
        ItemStack mainHand = player.getMainHandItem();
        if (!isMainHandGun(mainHand, player)) {
            ModularShoot.LOGGER.warn("Shoot rejected: main hand is not a gun (player={})", player.getName().getString());
            return;
        }
        GunData gunData = readGunData(mainHand);
        if (gunData == null) {
            // 竞态兜底：绑定枪械的 gun_data 由服务端 tick 通道在拿起 1 tick 内
            // 附加（设计规格 物品绑定系统 §5.3），但客户端可能在附加完成前就
            // 发出首枪——先尝试立即附加再重读一次。重读仍为空（非绑定物品，
            // 或绑定已被移除）才保留原有的拒绝日志。
            GunRegistry.ensureGunData(mainHand, player.registryAccess());
            gunData = readGunData(mainHand);
            if (gunData == null) {
                ModularShoot.LOGGER.warn("Shoot rejected: gun has no gun_data component (player={})", player.getName().getString());
                return;
            }
        }
        if (!validateModifierVersion(player, packetModifierVersion, gunData.modifierVersion())) {
            ModularShoot.LOGGER.warn("Shoot rejected: modifier version mismatch (player={}, packet={}, server={})",
                    player.getName().getString(), packetModifierVersion, gunData.modifierVersion());
            return;
        }
        // 审查优化（任务 1.2）：fire_rate 只在火控门禁解析一次，解析出的 final
        // 值同时传给 ShootingEngine.fire，避免 snapshot 阶段再次解析 FIRE_RATE。
        double fireRate = readFireRate(player);
        if (!checkFireRate(player, gunData.gunId(), fireRate)) {
            // Debug-level: when the client holds the shoot button it sends a
            // ShootC2SPacket every tick; most are rejected by the fire-rate
            // controller, so an INFO log here would flood the log (W9). Use
            // DEBUG so the rejection is visible only when diagnosing issues.
            // 审查优化 P8: isDebugEnabled 守卫 + 复用已解析的 fireRate——
            // 否则参数（getString + 完整属性解析链）在 DEBUG 关闭时也每 tick 求值。
            if (ModularShoot.LOGGER.isDebugEnabled()) {
                ModularShoot.LOGGER.debug("Shoot rejected by fire-rate controller (player={}, gun={}, fireRate={})",
                        player.getName().getString(), gunData.gunId(), fireRate);
            }
            return;
        }
        delegateToShootEngine(player, gunData, fireRate);
    }

    /**
     * Checks whether the main-hand item is a framework gun.
     *
     * <p>Delegates to {@link GunRecognition#isGun(ItemStack, RegistryAccess)}
     * with the player's runtime {@link RegistryAccess} so datapack-bound guns
     * are recognized (绑定感知识别).</p>
     *
     * @param mainHand the main-hand item stack
     * @param player   the shooting player whose registry access to use
     * @return {@code true} when the stack is a {@code modularshoot:gun} item
     */
    private static boolean isMainHandGun(ItemStack mainHand, ServerPlayer player) {
        return GunRecognition.isGun(mainHand, player.registryAccess());
    }

    /**
     * Reads the {@link GunData} component from a gun stack.
     *
     * @param gun the gun item stack
     * @return the {@link GunData}, or {@code null} when the stack carries no
     *         gun-data component
     */
    private static @Nullable GunData readGunData(ItemStack gun) {
        return gun.get(ModularShootDataComponents.GUN_DATA.get());
    }

    /**
     * Runs the modifier-version anti-cheat check.
     *
     * @param player        the shooting player
     * @param packetVersion the modifier version from the packet
     * @param serverVersion the server's current modifier version
     * @return {@code true} if the check passes; {@code false} if a cheat
     *         verdict was reached
     */
    private static boolean validateModifierVersion(ServerPlayer player, int packetVersion, int serverVersion) {
        return ModifierVersionAntiCheat.validate(player, packetVersion, serverVersion);
    }

    /**
     * Resolves the player's final {@code fire_rate} value once, following the
     * {@code attribute_meta} {@code binds} chain (see
     * {@link AttributeResolver#readFinalValue}). A missing link degrades to
     * {@code 0.0} and the fire-rate gate rejects the shot.
     *
     * @param player the shooting player
     * @return the final {@code fire_rate} attribute value (shots per second)
     */
    private static double readFireRate(ServerPlayer player) {
        return AttributeResolver.readFinalValue(
                player, ModularShootAttributes.FIRE_RATE.getKey().location(), player.registryAccess());
    }

    /**
     * Runs the fire-rate gate using the already-resolved final
     * {@code fire_rate} value resolved via the {@code attribute_meta}
     * {@code binds} chain (see {@link AttributeResolver#readFinalValue}); a
     * missing link in the chain degrades the value to {@code 0.0} and the gate
     * rejects the shot.
     *
     * @param player   the shooting player
     * @param gunId    the gun definition id
     * @param fireRate the final {@code fire_rate} value already resolved by
     *                 {@link #readFireRate}
     * @return {@code true} if the shot may proceed under the fire-rate limit
     */
    private static boolean checkFireRate(ServerPlayer player, ResourceLocation gunId, double fireRate) {
        return FireRateController.canShoot(player, gunId, fireRate);
    }

    /**
     * Delegates the accepted shoot request to the shooting engine for
     * look-angle derivation, spread, bullet snapshot creation,
     * {@code BulletManager} registration, sound playback and
     * {@code PostShootEvent} dispatch.
     *
     * @param player   the shooting player
     * @param gunData  the gun data of the main-hand gun
     * @param fireRate the final {@code fire_rate} value already resolved by
     *                 the fire-rate gate (reused by the engine snapshot)
     */
    private static void delegateToShootEngine(ServerPlayer player, GunData gunData, double fireRate) {
        ShootingEngine.fire(player, gunData, fireRate);
    }
}
