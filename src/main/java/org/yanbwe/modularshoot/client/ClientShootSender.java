package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.network.ShootC2SPacket;

/**
 * Client-side shoot-request sender — emits a {@link ShootC2SPacket} every tick
 * while the player holds the attack key with a gun in the main hand.
 *
 * <p>This implements the "request-per-tick" model described in the design doc
 * (§客户端射击请求发送机制). The client performs <em>no</em> rate-limiting and
 * makes <em>no</em> decision about whether a shot actually fires: it merely
 * streams a continuous "I want to shoot" signal to the server while the attack
 * key is held. The server is the sole authority — it validates the
 * {@code modifierVersion} (anti-cheat), enforces fire-rate via tick counting,
 * and derives the bullet direction from {@code getLookAngle()} so a hacked
 * client can never tamper with spread or aim.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus
 * ({@code NeoForge.EVENT_BUS}) with {@code value = Dist.CLIENT} so the class
 * is only loaded on the physical client. This prevents
 * {@code ClassNotFoundException} for {@link Minecraft} on a dedicated server.
 * {@link ClientTickEvent.Pre} fires once per client tick before the client
 * performs the tick's work, giving the server the earliest possible notice of
 * the shoot intent.</p>
 *
 * <p><b>Bandwidth:</b> each packet is ~20 bytes; at 20 packets/s the overhead
 * is ~400 bytes/s/player, which is negligible. A dropped packet simply means
 * one fewer shot that tick — no cascading desync.</p>
 *
 * @see ShootC2SPacket
 * @see org.yanbwe.modularshoot.shooting.LeftClickInterceptHandler
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class ClientShootSender {

    private ClientShootSender() {
    }

    /**
     * Polled every client tick; sends a shoot request when the attack key is
     * held with a gun in the main hand.
     *
     * <p>The method is deliberately a sequence of cheap guard clauses followed
     * by a single delegation to {@link #sendShootRequest(Player)}. The guards
     * short-circuit in order of increasing cost: the in-game check is a
     * constant-time null test, the key-down check is a field read, and only
     * then do we touch the inventory and data components.</p>
     *
     * @param event the pre client-tick event (unused beyond its presence)
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!isInGame(minecraft) || !minecraft.options.keyAttack.isDown()) {
            return;
        }
        sendShootRequest(minecraft.player);
    }

    /**
     * Determines whether the client is currently in a playable world.
     *
     * <p>{@link Minecraft#player} and {@link Minecraft#level} are {@code null}
     * on the main menu / config screens and during world transitions. Sending
     * a shoot packet in those states would crash, so this guard is
     * mandatory.</p>
     *
     * @param minecraft the client instance
     * @return {@code true} when both the local player and the client level are loaded
     */
    private static boolean isInGame(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null;
    }

    /**
     * Reads the main-hand gun's modifier version and sends a shoot request to
     * the server.
     *
     * <p>Skips silently when the main-hand item is not a gun or carries no
     * {@code gun_data} component — a gun stack should always have
     * {@code gun_data}, but the null guard defends against malformed
     * (e.g. command-spawned) stacks without throwing.</p>
     *
     * @param player the local player; guaranteed non-null by {@link #isInGame}
     */
    private static void sendShootRequest(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return;
        }
        @Nullable GunData gunData = mainHand.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        PacketDistributor.sendToServer(new ShootC2SPacket(gunData.modifierVersion()));
    }
}
