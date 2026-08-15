package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.bullet.BulletHitSoundResolver;
import org.yanbwe.modularshoot.bullet.BulletRecord;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side hit-broadcast utility with per-tick batching (阶段 2 / 任务 2.4
 * 命中广播聚合).
 *
 * <p>Previously each hit immediately built a {@link BulletHitS2CPacket} and
 * called {@link PacketDistributor#sendToPlayersNear} once &mdash; O(hits)
 * packets and O(hits) distance-filter scans per tick under high fire rates /
 * multi-projectile loads. Instead, hits are now accumulated for the
 * <em>current server tick</em> and flushed at {@link ServerTickEvent.Post}:
 * all hits of the tick are grouped per nearby player (one
 * {@link PlayerPos proximity scan} per tick) and each player with at least
 * one nearby hit receives a single {@link BulletHitBatchS2CPacket}. This
 * collapses O(hits) packets into O(nearby-players) batch packets and reduces
 * the per-tick broadcast calls accordingly.</p>
 *
 * <p>The broadcast radius is shared with {@link ShootAnimSyncService} so that
 * hit effects and shoot animations have a consistent visibility range. A
 * single constant ensures both services stay in sync if the radius ever
 * needs tuning.</p>
 *
 * <p><b>Not</b> an {@code @EventBusSubscriber} — this is a pure utility class
 * invoked imperatively by the tick handler at the moment a collision is
 * resolved. The {@code ServerTickEvent.Post} hook is the sole subscriber and
 * only drives the tick-end flush.</p>
 *
 * <p><b>Server-only.</b> Callers must ensure the level is a
 * {@link ServerLevel}; the tick handler already guards against client-side
 * execution before reaching collision resolution.</p>
 */
@EventBusSubscriber(modid = ModularShoot.MODID)
public final class BulletHitBroadcastService {

    /** Pending hits for the current tick, grouped by server level. */
    private static final Map<ServerLevel, List<BulletHitS2CPacket>> PENDING = new HashMap<>();

    private BulletHitBroadcastService() {
    }

    /**
     * Queues a bullet-hit event for the current server tick.
     *
     * <p>Builds a {@link BulletHitS2CPacket} from the supplied parameters and
     * defers distribution until the tick-end flush in {@link #flushAll}. The
     * packet's {@code soundId} is resolved eagerly (at hit time) from the
     * firing gun definition's {@code sounds} slots via
     * {@link BulletHitSoundResolver} — entity hits read {@code hit_entity},
     * block hits read {@code hit_block}, pierce hits read
     * {@code hit_pierce}. When a slot is not configured the value is
     * {@code null}, and clients play no sound (silent impact).</p>
     *
     * @param level       the server level in which the hit occurred
     * @param bullet      the bullet that hit; its id, snapshot and gun
     *                    definition drive the broadcast and sound resolution
     * @param hitPos      the exact world-space hit position
     * @param hitType     kind of hit (ENTITY / BLOCK / PIERCE)
     * @param hitEntityId network id of the hit entity, or
     *                    {@link BulletHitS2CPacket#NO_ENTITY} ({@code -1})
     *                    when the hit is not an entity
     */
    public static void broadcastHit(ServerLevel level, BulletRecord bullet, Vec3 hitPos,
                                    BulletHitS2CPacket.HitType hitType, int hitEntityId) {
        ResourceLocation soundId = BulletHitSoundResolver.resolve(bullet, level, hitType).orElse(null);
        BulletHitS2CPacket packet = new BulletHitS2CPacket(
                bullet.getBulletId(), hitPos.x, hitPos.y, hitPos.z, hitType, hitEntityId, soundId);
        PENDING.computeIfAbsent(level, k -> new ArrayList<>()).add(packet);
    }

    /**
     * Tick-end hook: flushes every pending hit batch after all dimensions
     * have ticked. Fires once per server tick, so all hits gathered during
     * the tick across all levels are aggregated and distributed in a single
     * pass.
     *
     * @param event the post server-tick event
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        flushAll(event.getServer());
    }

    /**
     * Flushes all pending hit batches across every loaded server level.
     *
     * <p>After flushing, any {@link #PENDING} key whose level is no longer
     * loaded (a dimension unloaded while hits were still queued) is pruned so
     * a static map never retains state for unloaded levels (review fix:
     * PENDING 静态 Map 残留已卸载维度).</p>
     *
     * @param server the server whose levels should be flushed
     */
    public static void flushAll(MinecraftServer server) {
        Set<ServerLevel> loaded = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            loaded.add(level);
            flush(level);
        }
        PENDING.keySet().removeIf(level -> !loaded.contains(level));
    }

    /**
     * Flushes one level's pending hits: groups them by nearby player and
     * sends one {@link BulletHitBatchS2CPacket} per player who is within
     * {@link ShootAnimSyncService#BROADCAST_RADIUS} of at least one hit.
     *
     * <p>The shooter is <em>not</em> excluded — every nearby player (including
     * the shooter) sees the same batch, matching the previous single-packet
     * behaviour where {@code null} was passed as the excluded player.</p>
     *
     * <p>Only online players <em>in {@code level}'s dimension</em> are
     * eligible, mirroring the previous {@link PacketDistributor#sendToPlayersNear}
     * which broadcast through {@code PlayerList#broadcast} against
     * {@code level.dimension()}. Players in other dimensions, even if their
     * coordinates would be within radius, never receive this level's hits
     * (review fix: flush 跨维度误发).</p>
     *
     * @param level the server level whose pending hits to distribute
     */
    public static void flush(ServerLevel level) {
        List<BulletHitS2CPacket> hits = PENDING.remove(level);
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        List<PlayerPos> allPlayerPositions = players.stream()
                .map(p -> new PlayerPos(p.getUUID(), p.serverLevel().dimension(), p.getX(), p.getY(), p.getZ()))
                .toList();
        Map<UUID, List<BulletHitS2CPacket>> grouped =
                buildBuffersForLevel(level.dimension(), ShootAnimSyncService.BROADCAST_RADIUS, hits, allPlayerPositions);
        for (Map.Entry<UUID, List<BulletHitS2CPacket>> entry : grouped.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                PacketDistributor.sendToPlayer(player, new BulletHitBatchS2CPacket(entry.getValue()));
            }
        }
    }

    /**
     * Builds the per-player hit buffers for one level's flush pass: first
     * restricts the candidate players to those in {@code dimension}, then
     * groups the hits by proximity to those players.
     *
     * <p>This is the <em>pure</em> aggregation path shared by the real
     * {@link #flush(ServerLevel)} (no {@code PacketDistributor} /
     * {@code ServerPlayer} required), so the dimension filtering + batching
     * contract can be unit-tested directly.</p>
     *
     * @param dimension   the dimension whose hits are being flushed
     * @param radius      broadcast radius in blocks
     * @param hits        the tick's hit packets for {@code dimension}
     * @param allPlayers  the online players' positions, in any dimension
     * @return per-player hit lists for players in {@code dimension} who are
     *         within radius of at least one hit; never {@code null}
     */
    static Map<UUID, List<BulletHitS2CPacket>> buildBuffersForLevel(
            ResourceKey<Level> dimension, double radius, List<BulletHitS2CPacket> hits,
            Collection<PlayerPos> allPlayers) {
        return groupByNearbyPlayer(radius, hits, playersInLevel(dimension, allPlayers));
    }

    /**
     * Filters a collection of player positions down to those whose dimension
     * matches {@code dimension}.
     *
     * <p>Pure dimension filter (no Minecraft server access), matching
     * {@link net.minecraft.server.players.PlayerList#broadcast}'s
     * {@code player.level().dimension() == resourceKey} test that the old
     * {@code sendToPlayersNear} relied on.</p>
     *
     * @param dimension  the dimension to retain
     * @param players    the candidate player positions (any dimension)
     * @return only the positions whose dimension equals {@code dimension}
     */
    static List<PlayerPos> playersInLevel(ResourceKey<Level> dimension, Collection<PlayerPos> players) {
        return players.stream()
                .filter(p -> p.dimension() == dimension)
                .toList();
    }

    /**
     * Groups a tick's hit packets by the players who are within
     * {@code radius} of at least one hit.
     *
     * <p>Pure grouping logic (no Minecraft server access) so it can be unit
     * tested directly: every player whose position lies within {@code radius}
     * of any hit's position maps to the {@link List} of hits they should
     * receive. Each returned list becomes a single
     * {@link BulletHitBatchS2CPacket} sent to that player.</p>
     *
     * @param radius  broadcast radius in blocks
     * @param hits    the tick's hit packets (each carrying a world position)
     * @param players the online players' positions to test for proximity
     * @return per-player hit lists, containing only players near at least one
     *         hit; never {@code null}
     */
    static Map<UUID, List<BulletHitS2CPacket>> groupByNearbyPlayer(
            double radius, List<BulletHitS2CPacket> hits, Collection<PlayerPos> players) {
        Map<UUID, List<BulletHitS2CPacket>> grouped = new HashMap<>();
        for (PlayerPos player : players) {
            List<BulletHitS2CPacket> near = new ArrayList<>();
            for (BulletHitS2CPacket hit : hits) {
                if (withinRadius(radius, player, hit)) {
                    near.add(hit);
                }
            }
            if (!near.isEmpty()) {
                grouped.put(player.uuid(), near);
            }
        }
        return grouped;
    }

    /**
     * Tests whether a player position is within {@code radius} of a hit point.
     *
     * @param radius the broadcast radius
     * @param player the player position
     * @param hit    the hit packet carrying the hit point
     * @return {@code true} if the player is within radius of the hit
     */
    private static boolean withinRadius(double radius, PlayerPos player, BulletHitS2CPacket hit) {
        double dx = player.x() - hit.hitX();
        double dy = player.y() - hit.hitY();
        double dz = player.z() - hit.hitZ();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /**
     * Plain per-player position + dimension value used by the pure grouping
     * logic, so tests need no {@link ServerPlayer}.
     *
     * <p>The dimension is carried so the exact same record can feed both the
     * dimension filter ({@link #playersInLevel}) and the proximity grouping
     * ({@link #groupByNearbyPlayer}) in one flush aggregation path.</p>
     *
     * @param uuid      the player's UUID
     * @param dimension the dimension the player is currently in
     * @param x         the player's x coordinate
     * @param y         the player's y coordinate
     * @param z         the player's z coordinate
     */
    public record PlayerPos(UUID uuid, ResourceKey<Level> dimension, double x, double y, double z) {
    }
}
