package org.yanbwe.modularshoot.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.yanbwe.modularshoot.bullet.BulletManager;
import org.yanbwe.modularshoot.network.BulletHitBatchS2CPacket;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket;
import org.yanbwe.modularshoot.network.BulletS2CPacket;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;
import org.yanbwe.modularshoot.network.PlayerStateS2CPacket;
import org.yanbwe.modularshoot.network.ShootAnimS2CPacket;
import org.yanbwe.modularshoot.state.ModularShootAttachmentTypes;

/**
 * Client-side payload handlers for every server-to-client (S→C) payload that
 * ModularShoot registers.
 *
 * <p><b>测试说明:</b> 本类无单元测试，枪械同步 / 子弹渲染 / 命中特效需
 * {@code runClient} 人工验证。</p>
 *
 * <p>Each method processes one S→C packet on the physical client. The handler
 * bodies were previously inlined in
 * {@link org.yanbwe.modularshoot.network.ModularShootPayloads}; they are
 * consolidated here so the <em>common</em> network class only handles payload
 * type registration plus the server-bound (C→S) handlers, and never references
 * {@code net.minecraft.client} classes directly (任务 6.2 — 客户端 payload
 * handler 移出 common).</p>
 *
 * <p><b>Client-only.</b> This class lives in the {@code client} package and is
 * only ever loaded when an S→C payload is actually handled on the physical
 * client. It is referenced from
 * {@link org.yanbwe.modularshoot.network.ModularShootPayloads} solely through
 * lazily-invoked lambda bodies, so a dedicated server never loads it — matching
 * the pattern of {@link ClientGunDataStore}, {@link ClientGunSyncHandler} and
 * {@link org.yanbwe.modularshoot.client.render.BulletRenderManager}.</p>
 *
 * <p><b>Threading:</b> NeoForge's {@code PayloadRegistrar} wraps every handler
 * so it executes on the main game thread by default; the methods below keep the
 * explicit {@link IPayloadContext#enqueueWork(Runnable)} calls so world/player
 * state is only touched on the main client thread.</p>
 */
public final class ClientPayloadHandlers {

    private ClientPayloadHandlers() {
    }

    /**
     * Handles a {@link BulletS2CPacket}.
     *
     * <p>Delegates to {@link BulletRenderManager#handlePacket(BulletS2CPacket)}
     * on the main client thread via
     * {@link IPayloadContext#enqueueWork(Runnable)}. The manager is obtained
     * through {@link BulletManager#getClientLevel(Level)} using the client
     * {@code Level} from {@link Minecraft#level}, keeping a single entry point
     * for bullet-manager lookups. The manager reconciles its render-object map
     * with the packet: creating new
     * {@link org.yanbwe.modularshoot.client.render.BulletRenderObject}s,
     * updating existing ones, and removing those whose bullets have
     * expired.</p>
     *
     * @param payload the sync packet from the server
     * @param context the payload context
     */
    public static void handleBulletS2C(BulletS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Level level = Minecraft.getInstance().level;
            if (level != null) {
                BulletManager.getClientLevel(level).handlePacket(payload);
            }
        });
    }

    /**
     * Handles a {@link BulletHitS2CPacket}.
     *
     * <p>Delegates to {@link ClientHitEffectHandler#playHitEffect} on the
     * main client thread via {@link IPayloadContext#enqueueWork(Runnable)}.
     * The handler posts a
     * {@link org.yanbwe.modularshoot.client.event.ClientBulletHitEvent}
     * (extensions may cancel it to take over hit effects entirely) and plays a
     * data-driven sound resolved by the server from the gun definition's
     * {@code sounds} slots ({@code payload.soundId()}; {@code null} means
     * silent) — no particles are spawned by the framework — without mutating
     * any game state: the server has already resolved damage
     * authoritatively.</p>
     *
     * @param payload the hit packet
     * @param context the payload context
     */
    public static void handleBulletHitS2C(BulletHitS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Vec3 hitPos = new Vec3(payload.hitX(), payload.hitY(), payload.hitZ());
            ClientHitEffectHandler.playHitEffect(
                    mc.level, hitPos, payload.hitType(), payload.hitEntityId(),
                    payload.soundId());
        });
    }

    /**
     * Handles a {@link BulletHitBatchS2CPacket}.
     *
     * <p>Runs on the main client thread via
     * {@link IPayloadContext#enqueueWork(Runnable)} and dispatches each
     * contained hit through the exact same {@link ClientHitEffectHandler}
     * effect pipeline as the single-hit {@link BulletHitS2CPacket}, so the
     * batched path is fully behaviour-compatible with the unbatched one — the
     * only difference is that several same-tick hits arrive in one payload
     * (阶段 2 / 任务 2.4 命中广播聚合).</p>
     *
     * @param payload the batched hit packet
     * @param context the payload context
     */
    public static void handleBulletHitBatchS2C(BulletHitBatchS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            for (BulletHitS2CPacket hit : payload.hits()) {
                Vec3 hitPos = new Vec3(hit.hitX(), hit.hitY(), hit.hitZ());
                ClientHitEffectHandler.playHitEffect(
                        mc.level, hitPos, hit.hitType(), hit.hitEntityId(), hit.soundId());
            }
        });
    }

    /**
     * Handles a {@link GunSyncS2CPacket}.
     *
     * <p>Delegates to two client-side consumers on the main client thread
     * via {@link IPayloadContext#enqueueWork(Runnable)}:</p>
     * <ol>
     *   <li>{@link ClientGunSyncHandler#handlePacket} rebuilds the local
     *       player's main-hand
     *       {@link org.yanbwe.modularshoot.component.GunData} from the
     *       packet's plugin list, {@code modifierVersion} and per-gun
     *       {@code state} map, keeping the client's gun model aligned with
     *       the server (and serving as a fallback data source for
     *       consumers).</li>
     *   <li>{@link ClientGunDataStore#handleSync} stores the same snapshot
     *       in a dedicated singleton so the plugin overlay compositor and
     *       state tooltip builder can read from an explicit sync channel.</li>
     * </ol>
     *
     * <p>Both consumers are gated behind
     * {@link ClientGunSyncHandler#isForMainHand}: a snapshot whose
     * {@code gunInstanceUuid} no longer matches the current main-hand gun
     * (stale sync arriving after a weapon switch) is dropped wholesale so
     * neither the stack component nor the store is contaminated with the
     * previous gun's data (描边污染修复).</p>
     *
     * @param payload the gun-sync packet
     * @param context the payload context
     */
    public static void handleGunSyncS2C(GunSyncS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (ClientGunSyncHandler.isForMainHand(payload)) {
                ClientGunSyncHandler.handlePacket(payload);
                ClientGunDataStore.getInstance().handleSync(payload);
            }
        });
    }

    /**
     * Handles a {@link ShootAnimS2CPacket}.
     *
     * <p>Delegates to {@link PlayerShootStateManager#handlePacket} on the main
     * client thread via {@link IPayloadContext#enqueueWork}, which updates the
     * per-player animation state for the remote player identified by the
     * packet. The local player's own state is maintained with zero delay by
     * the manager itself and is ignored by {@code handlePacket}.</p>
     *
     * @param payload the shoot-anim packet
     * @param context the payload context
     */
    public static void handleShootAnimS2C(ShootAnimS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> PlayerShootStateManager.getInstance().handlePacket(payload));
    }

    /**
     * Handles a {@link PlayerStateS2CPacket}.
     *
     * <p>Applies the throttled per-player state sync on the main client thread
     * via {@link IPayloadContext#enqueueWork(Runnable)}: the received
     * {@link org.yanbwe.modularshoot.state.PlayerStateData} is installed onto
     * the local player through {@code setData}, so subsequent
     * {@code PlayerState.of(player)} reads reflect the server's
     * eventually-consistent state (任务 3.3 — PlayerState 同步节流).</p>
     *
     * @param payload the per-player state packet
     * @param context the payload context
     */
    public static void handlePlayerStateS2C(PlayerStateS2CPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.setData(
                        ModularShootAttachmentTypes.PLAYER_STATE.get(), payload.data());
            }
        });
    }
}
