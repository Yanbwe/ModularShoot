package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
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
import org.yanbwe.modularshoot.client.keybind.ActionKeyBinding;
import org.yanbwe.modularshoot.network.ActionC2SPacket;

/**
 * Client-side action-key forwarder — sends a {@link ActionC2SPacket} to the
 * server when the player single-presses the action key (default: R) with a
 * gun in the main hand.
 *
 * <p>This class bridges the key-binding layer
 * ({@link ActionKeyBinding}) and the network layer
 * ({@link ActionC2SPacket}). It performs <em>no</em> action logic on the
 * client — it only forwards the action <em>intent</em> to the server, which
 * is the sole authority for firing {@link org.yanbwe.modularshoot.api.event.ActionEvent}.</p>
 *
 * <p><b>Registration:</b> registered on the NeoForge game event bus
 * ({@code NeoForge.EVENT_BUS}) with {@code value = Dist.CLIENT} so the class
 * is only loaded on the physical client. This prevents
 * {@code ClassNotFoundException} for {@link Minecraft} on a dedicated
 * server.</p>
 *
 * <p><b>Tick ordering:</b> {@link ClientTickEvent.Pre} is also listened to by
 * {@link ActionKeyBinding}, which latches the press flag
 * {@link ActionKeyBinding#isActionPressed()} each tick. This subscriber uses
 * {@link EventPriority#LOW} so it runs <em>after</em> the key-binding handler
 * (default priority {@code NORMAL}), guaranteeing the flag is fresh for the
 * current tick when read here. Without the priority, the handler might read
 * the previous tick's flag and send the packet one tick late.</p>
 *
 * <p><b>Gun guard:</b> the client checks
 * {@link ModularShootAPI#isGun(ItemStack, RegistryAccess)} on the main-hand
 * item before sending, avoiding a wasted round-trip for non-gun items. The
 * server
 * re-checks this anyway (defense in depth against a hacked client).</p>
 *
 * @see ActionKeyBinding for the key binding and press-detection logic
 * @see ActionC2SPacket for the packet wire format
 * @see ClientShootSender for the analogous C→S forwarder for shooting
 */
@EventBusSubscriber(modid = ModularShoot.MODID, value = Dist.CLIENT)
public final class ActionKeyHandler {

    private ActionKeyHandler() {
    }

    /**
     * Polled every client tick; sends an action request when the action key
     * was single-pressed this tick with a gun in the main hand.
     *
     * <p>Uses {@link EventPriority#LOW} to ensure
     * {@link ActionKeyBinding#onClientTick} has already latched the
     * {@link ActionKeyBinding#isActionPressed()} flag for this tick before
     * we read it.</p>
     *
     * @param event the pre client-tick event (unused beyond its presence)
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (!ActionKeyBinding.isActionPressed()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!isInGame(minecraft)) {
            return;
        }
        sendActionRequest(minecraft.player);
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
     * Checks the main-hand item is a gun and sends an action request to the
     * server.
     *
     * <p>Skips silently when the main-hand item is not a gun — the server
     * would reject the packet anyway, so the client avoids the round-trip.</p>
     *
     * @param player the local player; guaranteed non-null by {@link #isInGame}
     */
    private static void sendActionRequest(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (!ModularShootAPI.isGun(mainHand, player.registryAccess())) {
            return;
        }
        PacketDistributor.sendToServer(new ActionC2SPacket());
    }
}
