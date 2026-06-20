package org.yanbwe.modularshoot.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side hit-broadcast utility (设计文档 §BulletHitS2CPacket).
 *
 * <p>Provides a single static entry point that {@code BulletTickHandler}
 * calls when a bullet impacts an entity or block. The service constructs a
 * {@link BulletHitS2CPacket} from the hit parameters and distributes it to
 * every player within {@link #BROADCAST_RADIUS} blocks of the hit point via
 * {@link PacketDistributor#sendToPlayersNear}.</p>
 *
 * <p><b>Not</b> an {@code @EventBusSubscriber} — this is a pure utility class
 * invoked imperatively by the tick handler at the moment a collision is
 * resolved. Keeping the broadcast logic here lets the tick handler stay
 * focused on simulation while this class owns the network distribution
 * concern (single responsibility).</p>
 *
 * <p><b>Server-only.</b> Callers must ensure the level is a
 * {@link ServerLevel}; the tick handler already guards against client-side
 * execution before reaching collision resolution.</p>
 */
public final class BulletHitBroadcastService {

    /**
     * Radius (in blocks) around the hit point within which players receive
     * the hit packet. 64 blocks is far enough for any player who could
     * plausibly see the impact effect, while avoiding unnecessary network
     * traffic to distant players.
     */
    private static final double BROADCAST_RADIUS = 64.0;

    private BulletHitBroadcastService() {
    }

    /**
     * Broadcasts a bullet-hit event to all players near the hit position.
     *
     * <p>Builds a {@link BulletHitS2CPacket} from the supplied parameters and
     * sends it via {@link PacketDistributor#sendToPlayersNear} with
     * {@code null} excluded player (no one is skipped — even the shooter
     * should see their own hit marker).</p>
     *
     * @param level       the server level in which the hit occurred
     * @param bulletId    the id of the bullet that hit
     * @param hitPos      the exact world-space hit position
     * @param hitType     kind of hit (ENTITY / BLOCK / PIERCE)
     * @param hitEntityId network id of the hit entity, or
     *                    {@link BulletHitS2CPacket#NO_ENTITY} ({@code -1})
     *                    when the hit is not an entity
     */
    public static void broadcastHit(ServerLevel level, int bulletId, Vec3 hitPos,
                                    BulletHitS2CPacket.HitType hitType, int hitEntityId) {
        BulletHitS2CPacket packet = new BulletHitS2CPacket(
                bulletId, hitPos.x, hitPos.y, hitPos.z, hitType, hitEntityId);
        PacketDistributor.sendToPlayersNear(
                level, null, hitPos.x, hitPos.y, hitPos.z, BROADCAST_RADIUS, packet);
    }
}
