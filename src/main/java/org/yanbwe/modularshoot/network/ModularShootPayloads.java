package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Central registration of all {@link net.minecraft.network.protocol.common.custom.CustomPacketPayload
 * custom payloads} for ModularShoot, wired through NeoForge 1.21.1's
 * {@link RegisterPayloadHandlersEvent} + {@link PayloadRegistrar} system
 * (not the legacy {@code SimpleChannel}).
 *
 * <p>This class is auto-registered on the <em>mod event bus</em> via
 * {@link EventBusSubscriber}, so the main mod class does not need to reference
 * it — the {@link #onRegisterPayloads(RegisterPayloadHandlersEvent)} method is
 * discovered and invoked reflectively by NeoForge.</p>
 *
 * <p><b>Protocol version:</b> all payloads are registered under version
 * {@value #PROTOCOL_VERSION}. On Neo-to-Neo connections the handshake requires
 * both sides to agree on this version; on vanilla/FML connections the version
 * is ignored. Bump this constant whenever a payload's wire format changes in a
 * backwards-incompatible way.</p>
 *
 * <p><b>Threading:</b> {@link PayloadRegistrar} wraps every handler so it
 * executes on the main game thread by default, so handlers below may safely
 * touch world/player state without {@link IPayloadContext#enqueueWork(Runnable)}.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootPayloads {

    /** Network protocol version shared by all ModularShoot payloads. */
    public static final String PROTOCOL_VERSION = "1";

    private ModularShootPayloads() {}

    /**
     * Registers every ModularShoot payload with NeoForge's network registry.
     *
     * <p>Fired on the mod event bus during network setup. A fresh
     * {@link PayloadRegistrar} is obtained from the event (scoped to
     * {@link #PROTOCOL_VERSION}) and used to register each payload with its
     * {@link net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type},
     * {@link net.minecraft.network.codec.StreamCodec}, and handler.</p>
     *
     * @param event the payload-registration event
     */
    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registerShootC2S(registrar);
    }

    /**
     * Registers {@link ShootC2SPacket} as a play-phase, server-bound payload
     * (C→S direction) and binds its handler stub.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerShootC2S(PayloadRegistrar registrar) {
        registrar.playToServer(ShootC2SPacket.TYPE, ShootC2SPacket.STREAM_CODEC, handleShootC2S());
    }

    /**
     * Builds the handler for {@link ShootC2SPacket}.
     *
     * <p><b>Stub:</b> the full server-side shoot pipeline (gun validation,
     * modifier-version anti-cheat, fire-rate control, look-angle derivation,
     * bullet snapshot creation, {@code BulletManager} registration, and
     * {@code BulletS2CPacket} broadcast) is implemented in the shooting-engine
     * subtasks. Until then this handler only confirms the packet reaches the
     * server and resolves the sending player.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<ShootC2SPacket> handleShootC2S() {
        return (payload, context) -> {
            ServerPlayer player = (ServerPlayer) context.player();
            // TODO(subtask 16/17): delegate to ShootEngine.handleShootRequest(player, payload.modifierVersion())
            ModularShoot.LOGGER.debug(
                    "Received ShootC2SPacket from {} with modifierVersion={}",
                    player.getName().getString(),
                    payload.modifierVersion());
        };
    }
}
