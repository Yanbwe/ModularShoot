package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.ModularShootAPI;
import org.yanbwe.modularshoot.client.keybind.ReloadKeyBinding;
import org.yanbwe.modularshoot.network.ReloadC2SPacket;

/**
 * Client-side reload-key forwarder — sends a {@link ReloadC2SPacket} to the
 * server when the player single-presses the reload key (default: R) with a
 * gun in the main hand.
 *
 * <p>This class bridges the key-binding layer
 * ({@link ReloadKeyBinding}) and the network layer
 * ({@link ReloadC2SPacket}). It performs <em>no</em> reload logic on the
 * client — it only forwards the reload <em>intent</em> to the server, which
 * is the sole authority for firing {@link org.yanbwe.modularshoot.api.event.ReloadEvent}.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus
 * ({@code NeoForge.EVENT_BUS}) with {@code value = Dist.CLIENT} so the class
 * is only loaded on the physical client. This prevents
 * {@code ClassNotFoundException} for {@link Minecraft} on a dedicated
 * server.</p>
 *
 * <p><b>Tick ordering:</b> {@link ClientTickEvent.Pre} is also listened to by
 * {@link ReloadKeyBinding}, which latches the press flag
 * {@link ReloadKeyBinding#isReloadPressed()} each tick. This subscriber uses
 * {@link EventPriority#LOW} so it runs <em>after</em> the key-binding handler
 * (default priority {@code NORMAL}), guaranteeing the flag is fresh for the
 * current tick when read here. Without the priority, the handler might read
 * the previous tick's flag and send the packet one tick late.</p>
 *
 * <p><b>Gun guard:</b> the client checks
 * {@link ModularShootAPI#isGun(ItemStack)} on the main-hand item before
 * sending, avoiding a wasted round-trip for non-gun items. The server
 * re-checks this anyway (defense in depth against a hacked client).</p>
 *
 * @see ReloadKeyBinding for the key binding and press-detection logic
 * @see ReloadC2SPacket for the packet wire format
 * @see ClientShootSender for the analogous C→S forwarder for shooting
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class ReloadKeyHandler {

    private ReloadKeyHandler() {
    }

    /**
     * Polled every client tick; sends a reload request when the reload key
     * was single-pressed this tick with a gun in the main hand.
     *
     * <p>Uses {@link EventPriority#LOW} to ensure
     * {@link ReloadKeyBinding#onClientTick} has already latched the
     * {@link ReloadKeyBinding#isReloadPressed()} flag for this tick before
     * we read it.</p>
     *
     * @param event the pre client-tick event (unused beyond its presence)
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (!ReloadKeyBinding.isReloadPressed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!isInGame(minecraft)) {
            return;
        }
        sendReloadRequest(minecraft.player);
    }

    /**
     * Determines whether the client is currently in a playable world.
     *
     * @param minecraft the client instance
     * @return {@code true} when both the local player and the client level are loaded
     */
    private static boolean isInGame(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null;
    }

    /**
     * Checks the main-hand item is a gun and sends a reload request to the
     * server.
     *
     * <p>Skips silently when the main-hand item is not a gun — the server
     * would reject the packet anyway, so the client avoids the round-trip.</p>
     *
     * @param player the local player; guaranteed non-null by {@link #isInGame}
     */
    private static void sendReloadRequest(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand)) {
            return;
        }
        PacketDistributor.sendToServer(new ReloadC2SPacket());
    }
}
