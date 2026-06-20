package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.client.ClientGunSyncHandler;
import org.yanbwe.modularshoot.client.PlayerShootStateManager;
import org.yanbwe.modularshoot.client.render.BulletRenderManager;
import org.yanbwe.modularshoot.shooting.ShootPacketHandler;

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
        // C → S (client-to-server)
        registerShootC2S(registrar);
        // S → C (server-to-client)
        registerBulletS2C(registrar);
        registerBulletHitS2C(registrar);
        registerGunSyncS2C(registrar);
        registerShootAnimS2C(registrar);
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
     * <p>Delegates to {@link ShootPacketHandler#handleShootRequest} which runs
     * the full server-side pipeline: gun validation, modifier-version
     * anti-cheat, fire-rate control, and (once implemented) the shooting
     * engine that derives the look angle, applies spread, creates the bullet
     * snapshot, registers the bullet with the {@code BulletManager} and
     * broadcasts the result.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<ShootC2SPacket> handleShootC2S() {
        return (payload, context) -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ShootPacketHandler.handleShootRequest(player, payload.modifierVersion());
        };
    }

    // ------------------------------------------------------------------
    //  S → C  (server-to-client) payload registrations
    // ------------------------------------------------------------------

    /**
     * Registers {@link BulletS2CPacket} as a play-phase, client-bound payload
     * (S→C direction) and binds its handler.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerBulletS2C(PayloadRegistrar registrar) {
        registrar.playToClient(BulletS2CPacket.TYPE, BulletS2CPacket.STREAM_CODEC, handleBulletS2C());
    }

    /**
     * Registers {@link BulletHitS2CPacket} as a play-phase, client-bound
     * payload (S→C direction) and binds its handler stub.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerBulletHitS2C(PayloadRegistrar registrar) {
        registrar.playToClient(BulletHitS2CPacket.TYPE, BulletHitS2CPacket.STREAM_CODEC, handleBulletHitS2C());
    }

    /**
     * Registers {@link GunSyncS2CPacket} as a play-phase, client-bound payload
     * (S→C direction) and binds its handler stub.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerGunSyncS2C(PayloadRegistrar registrar) {
        registrar.playToClient(GunSyncS2CPacket.TYPE, GunSyncS2CPacket.STREAM_CODEC, handleGunSyncS2C());
    }

    /**
     * Registers {@link ShootAnimS2CPacket} as a play-phase, client-bound
     * payload (S→C direction) and binds its handler stub.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerShootAnimS2C(PayloadRegistrar registrar) {
        registrar.playToClient(ShootAnimS2CPacket.TYPE, ShootAnimS2CPacket.STREAM_CODEC, handleShootAnimS2C());
    }

    // ------------------------------------------------------------------
    //  S → C  handler stubs (filled in by later M4 subtasks)
    // ------------------------------------------------------------------

    /**
     * Builds the handler for {@link BulletS2CPacket}.
     *
     * <p>Delegates to {@link BulletRenderManager#handlePacket(BulletS2CPacket)}
     * on the main client thread via
     * {@link IPayloadContext#enqueueWork(Runnable)}. The manager reconciles
     * its render-object map with the packet: creating new
     * {@link org.yanbwe.modularshoot.client.render.BulletRenderObject}s,
     * updating existing ones, and removing those whose bullets have
     * expired.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<BulletS2CPacket> handleBulletS2C() {
        return (payload, context) -> {
            context.enqueueWork(() -> BulletRenderManager.getInstance().handlePacket(payload));
        };
    }

    /**
     * Builds the handler for {@link BulletHitS2CPacket}.
     *
     * <p><b>STUB:</b> No-op until the client-side impact-effect logic is
     * implemented in a later M4 subtask.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<BulletHitS2CPacket> handleBulletHitS2C() {
        return (payload, context) -> {
            // Stub: client-side hit-effect handling to be implemented later.
        };
    }

    /**
     * Builds the handler for {@link GunSyncS2CPacket}.
     *
     * <p>Delegates to {@link ClientGunSyncHandler#handlePacket} on the main
     * client thread via {@link IPayloadContext#enqueueWork}. The handler
     * rebuilds the local player's main-hand
     * {@link org.yanbwe.modularshoot.component.GunData} from the packet's
     * plugin list, {@code modifierVersion} and per-gun {@code state} map,
     * keeping the client's gun model, plugin visual overlays, and HUD state
     * text aligned with the authoritative server state.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<GunSyncS2CPacket> handleGunSyncS2C() {
        return (payload, context) -> {
            context.enqueueWork(() -> ClientGunSyncHandler.handlePacket(payload));
        };
    }

    /**
     * Builds the handler for {@link ShootAnimS2CPacket}.
     *
     * <p>Delegates to {@link PlayerShootStateManager#handlePacket} on the main
     * client thread via {@link IPayloadContext#enqueueWork}, which updates the
     * per-player animation state for the remote player identified by the
     * packet. The local player's own state is maintained with zero delay by
     * the manager itself and is ignored by {@code handlePacket}.</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<ShootAnimS2CPacket> handleShootAnimS2C() {
        return (payload, context) -> {
            context.enqueueWork(() -> PlayerShootStateManager.getInstance().handlePacket(payload));
        };
    }
}
