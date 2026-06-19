package org.yanbwe.modularshoot.shooting;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.attribute.ModularShootAttributes;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;

/**
 * Server-side orchestrator for
 * {@link org.yanbwe.modularshoot.network.ShootC2SPacket ShootC2SPacket}
 * handling.
 *
 * <p>Implements the server-side processing pipeline from 设计文档 §网络同步方案
 * (服务端处理), steps 1&ndash;3 plus the fire-rate gate, and delegates the
 * remaining bullet-spawn work to the shooting engine (subtask 17).</p>
 *
 * <p><b>Pipeline:</b></p>
 * <ol>
 *   <li>Validate the main-hand item is a {@code modularshoot:gun}.</li>
 *   <li>Read {@link GunData} (modifier version + gun id) from the stack.</li>
 *   <li>Run the {@link ModifierVersionAntiCheat} modifier-version check.</li>
 *   <li>Run the {@link FireRateController} tick-based fire-rate gate using
 *       the player's final {@code fire_rate} attribute value.</li>
 *   <li>Delegate to the shooting engine (TODO subtask 17) for look-angle
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
        ItemStack mainHand = player.getMainHandItem();
        if (!isMainHandGun(mainHand)) {
            return;
        }
        GunData gunData = readGunData(mainHand);
        if (gunData == null) {
            return;
        }
        if (!validateModifierVersion(player, packetModifierVersion, gunData.modifierVersion())) {
            return;
        }
        if (!checkFireRate(player, gunData.gunId())) {
            return;
        }
        delegateToShootEngine(player, gunData);
    }

    /**
     * Checks whether the main-hand item is a framework gun.
     *
     * @param mainHand the main-hand item stack
     * @return {@code true} when the stack is a {@code modularshoot:gun} item
     */
    private static boolean isMainHandGun(ItemStack mainHand) {
        return ModularShootAPI.isGun(mainHand);
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
     * Runs the fire-rate gate using the player's final {@code fire_rate}
     * attribute value.
     *
     * @param player the shooting player
     * @param gunId  the gun definition id
     * @return {@code true} if the shot may proceed under the fire-rate limit
     */
    private static boolean checkFireRate(ServerPlayer player, ResourceLocation gunId) {
        double fireRate = player.getAttributeValue(ModularShootAttributes.FIRE_RATE);
        return FireRateController.canShoot(player, gunId, fireRate);
    }

    /**
     * Delegates the accepted shoot request to the shooting engine for
     * look-angle derivation, spread, bullet snapshot creation,
     * {@code BulletManager} registration and broadcast.
     *
     * <p><b>TODO (subtask 17):</b> the shooting engine is not yet
     * implemented. Until then this method only logs the accepted request at
     * {@code debug} level so the pipeline can be exercised end-to-end without
     * spawning bullets.</p>
     *
     * @param player  the shooting player
     * @param gunData the gun data of the main-hand gun
     */
    private static void delegateToShootEngine(ServerPlayer player, GunData gunData) {
        // TODO(subtask 17): invoke ShootEngine.fire(player, gunData) once implemented.
        ModularShoot.LOGGER.debug(
                "Shoot request accepted for player {} with gun {} (modifierVersion={})",
                player.getName().getString(),
                gunData.gunId(),
                gunData.modifierVersion());
    }
}
