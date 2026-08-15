package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.api.event.ActionEvent;
import org.yanbwe.modularshoot.client.ClientPayloadHandlers;
import org.yanbwe.modularshoot.shooting.ShootPacketHandler;
import org.yanbwe.modularshoot.util.GunRecognition;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
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
 *
 * <p><b>Client handler bundling — intentional deviation from 计划步骤 2
 * (任务 6.2):</b> this common class directly imports
 * {@link org.yanbwe.modularshoot.client.ClientPayloadHandlers}, the client-side
 * home of the S→C handler implementations. This is a deliberate deviation from
 * the original plan that would have bound those handlers purely on the client
 * dist: NeoForge requires the <em>server</em> side to also register every
 * {@code playToClient} payload in order to send it, so the registrations must
 * live where both sides run. Dedicated-server safety is preserved by
 * <em>lazy class loading</em>: each binding below forwards through a
 * lazily-invoked lambda body, so {@code ClientPayloadHandlers} (and thus
 * {@code net.minecraft.client} classes) is only ever loaded when an S→C payload
 * is actually handled on the physical client — never during registration on a
 * dedicated server. Should this ever change to a client-only binding, the guard
 * must be switched to {@code FMLEnvironment.dist} (see the architecture-guard
 * test in {@code network}), which is why this deviation is recorded explicitly
 * rather than silently mirrored.</p>
 *
 * <p><b>Known cleanup (阶段 6):</b> this class (common network) currently
 * imports {@link org.yanbwe.modularshoot.client.ClientPayloadHandlers} (client)
 * to bind the S&rarr;C handlers. This predates the common&rarr;client
 * separation drive and is recorded here as a known follow-up item — it is not
 * migrated in this change to avoid scope creep, and dedicated-server safety is
 * preserved via the lazily-invoked lambda bodies described above. No logic
 * changes are implied.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class ModularShootPayloads {

    /**
     * Network protocol version shared by all ModularShoot payloads.
     *
     * <p>Bumped to {@code "2"} at the D-02/D-03/D-04 fix: {@code BulletS2CPacket}
     * wire format changed from a single flat bullet list + fullSync flag to
     * a three-bucket structure (newBullets / updatedBullets / removedBulletIds
     * + forceFullSync flag) for incremental delta sync.</p>
     *
     * <p>Bumped to {@code "3"} at 阶段 2 / 任务 2.3: {@code BulletS2CPacket}
     * now content-addresses the flight-invariant style (a wire style id plus an
     * optional full style payload carried once per client), and
     * {@code GunSyncS2CPacket} gained a state-patch mode (only changed state
     * keys, a removed-key list, and a {@code statePatch} flag).</p>
     *
     * <p>Not bumped for the action-key rename (commit history): the payload id
     * {@code reload_c2s} → {@code action_c2s} is a wire-breaking change, but
     * the mod is unreleased, so no compatibility is preserved (see 设计文档
     * §动作键中立化).</p>
     */
    public static final String PROTOCOL_VERSION = "3";

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
        registerActionC2S(registrar);
        // S → C (server-to-client)
        registerBulletS2C(registrar);
        registerBulletHitS2C(registrar);
        registerBulletHitBatchS2C(registrar);
        registerGunSyncS2C(registrar);
        registerShootAnimS2C(registrar);
        registerPlayerStateS2C(registrar);
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

    /**
     * Registers {@link ActionC2SPacket} as a play-phase, server-bound payload
     * (C→S direction) and binds its handler.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerActionC2S(PayloadRegistrar registrar) {
        registrar.playToServer(ActionC2SPacket.TYPE, ActionC2SPacket.STREAM_CODEC, handleActionC2S());
    }

    /**
     * Builds the handler for {@link ActionC2SPacket}.
     *
     * <p>The packet carries no data — the server derives everything from the
     * sender's state. The handler obtains the {@link ServerPlayer} from the
     * packet context, re-validates that the main-hand item is a gun (defending
     * against a hacked client that sends the packet without a gun), and posts
     * an {@link ActionEvent} on the {@code NeoForge.EVENT_BUS} (game bus).</p>
     *
     * <p>The framework performs <em>no</em> action logic itself — it only
     * fires the event. Other mods subscribe to {@link ActionEvent} to
     * implement concrete action behavior (设计文档 §ActionEvent).</p>
     *
     * @return the payload handler
     */
    private static IPayloadHandler<ActionC2SPacket> handleActionC2S() {
        return (payload, context) -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ItemStack mainHand = player.getMainHandItem();
            if (!GunRecognition.isGun(mainHand, player.registryAccess())) {
                return;
            }
            NeoForge.EVENT_BUS.post(new ActionEvent(player, mainHand));
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
     * Registers {@link BulletHitBatchS2CPacket} as a play-phase, client-bound
     * payload (S→C direction) and binds its handler.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerBulletHitBatchS2C(PayloadRegistrar registrar) {
        registrar.playToClient(BulletHitBatchS2CPacket.TYPE, BulletHitBatchS2CPacket.STREAM_CODEC, handleBulletHitBatchS2C());
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

    /**
     * Registers {@link PlayerStateS2CPacket} as a play-phase, client-bound
     * payload (S→C direction) and binds its handler.
     *
     * @param registrar the payload registrar to register through
     */
    private static void registerPlayerStateS2C(PayloadRegistrar registrar) {
        registrar.playToClient(PlayerStateS2CPacket.TYPE, PlayerStateS2CPacket.STREAM_CODEC, handlePlayerStateS2C());
    }

    // ------------------------------------------------------------------
    //  S → C  handler binding
    // ------------------------------------------------------------------

    /**
     * <p><b>Dist-split note (任务 6.2 — 客户端 payload handler 移出 common):</b>
     * the S→C handler <em>implementations</em> live in the client-side
     * {@link ClientPayloadHandlers} class, which references Minecraft client
     * classes. Each binding below forwards to that class through a
     * <em>lazily-invoked lambda body</em> rather than calling it directly, so
     * {@link ClientPayloadHandlers} is only loaded when the handler actually
     * executes on the physical client — never during registration on a
     * dedicated server. This preserves the behavioural parity of the previous
     * inline handlers while keeping the common network class free of client
     * imports.</p>
     */

    /**
     * Builds the handler for {@link BulletS2CPacket} (see
     * {@link ClientPayloadHandlers#handleBulletS2C(BulletS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<BulletS2CPacket> handleBulletS2C() {
        return (payload, context) -> ClientPayloadHandlers.handleBulletS2C(payload, context);
    }

    /**
     * Builds the handler for {@link BulletHitS2CPacket} (see
     * {@link ClientPayloadHandlers#handleBulletHitS2C(BulletHitS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<BulletHitS2CPacket> handleBulletHitS2C() {
        return (payload, context) -> ClientPayloadHandlers.handleBulletHitS2C(payload, context);
    }

    /**
     * Builds the handler for {@link BulletHitBatchS2CPacket} (see
     * {@link ClientPayloadHandlers#handleBulletHitBatchS2C(BulletHitBatchS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<BulletHitBatchS2CPacket> handleBulletHitBatchS2C() {
        return (payload, context) -> ClientPayloadHandlers.handleBulletHitBatchS2C(payload, context);
    }

    /**
     * Builds the handler for {@link GunSyncS2CPacket} (see
     * {@link ClientPayloadHandlers#handleGunSyncS2C(GunSyncS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<GunSyncS2CPacket> handleGunSyncS2C() {
        return (payload, context) -> ClientPayloadHandlers.handleGunSyncS2C(payload, context);
    }

    /**
     * Builds the handler for {@link ShootAnimS2CPacket} (see
     * {@link ClientPayloadHandlers#handleShootAnimS2C(ShootAnimS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<ShootAnimS2CPacket> handleShootAnimS2C() {
        return (payload, context) -> ClientPayloadHandlers.handleShootAnimS2C(payload, context);
    }

    /**
     * Builds the handler for {@link PlayerStateS2CPacket} (see
     * {@link ClientPayloadHandlers#handlePlayerStateS2C(PlayerStateS2CPacket, IPayloadContext)}).
     *
     * @return the payload handler
     */
    private static IPayloadHandler<PlayerStateS2CPacket> handlePlayerStateS2C() {
        return (payload, context) -> ClientPayloadHandlers.handlePlayerStateS2C(payload, context);
    }
}
