package org.yanbwe.modularshoot.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;
import org.yanbwe.modularshoot.network.BulletHitBroadcastService.PlayerPos;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batching tests for 阶段 2 / 任务 2.4: 命中与动画广播聚合.
 *
 * <p>Verifies the aggregation contracts without touching the actual network
 * stack (no {@code PacketDistributor}/{@code ServerPlayer} needed):
 * <ol>
 *   <li><b>Bullet hits</b>: every hit broadcast within the same tick is
 *       grouped per nearby player so the tick's hits collapse into a single
 *       batch packet per player instead of one packet per hit, and the real
 *       flush aggregation path ({@link BulletHitBroadcastService#buildBuffersForLevel})
 *       only ever targets players in the hit's own dimension.</li>
 *   <li><b>Shoot animation</b>: a player broadcasts at most once per tick,
 *       multiple {@code onShootFired} calls in the same tick keep only the
 *       peak animation timer, and a same-tick fire+timeout flip never merges
 *       into a contradictory {@code (false, PEAK)} packet.</li>
 * </ol></p>
 */
class BroadcastBatchingTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);
    }

    // ------------------------------------------------------------------
    //  Bullet-hit batching
    // ------------------------------------------------------------------

    @Test
    void sameTickHitsAreGroupedIntoOneBatchPerNearbyPlayer() {
        UUID playerId = UUID.randomUUID();
        List<BulletHitS2CPacket> hits = List.of(
                new BulletHitS2CPacket(1, 10.0, 64.0, -5.0, HitType.ENTITY, 42, null),
                new BulletHitS2CPacket(2, 10.5, 64.0, -4.5, HitType.BLOCK, BulletHitS2CPacket.NO_ENTITY, null));

        Map<UUID, List<BulletHitS2CPacket>> grouped = BulletHitBroadcastService.groupByNearbyPlayer(
                64.0, hits, List.of(new PlayerPos(playerId, Level.OVERWORLD, 10.0, 64.0, -5.0)));

        assertEquals(1, grouped.size(), "one nearby player → exactly one batch group");
        List<BulletHitS2CPacket> batch = grouped.get(playerId);
        assertEquals(2, batch.size(), "both same-tick hits merge into a single batch for that player");
        assertEquals(1, batch.get(0).bulletId());
        assertEquals(2, batch.get(1).bulletId());
    }

    @Test
    void sameTickHitsProduceOneBatchPacketPerPlayerAfterCodecRoundTrip() {
        UUID playerId = UUID.randomUUID();
        List<BulletHitS2CPacket> hits = List.of(
                new BulletHitS2CPacket(1, 10.0, 64.0, -5.0, HitType.PIERCE, 7, null),
                new BulletHitS2CPacket(2, 12.0, 64.0, -6.0, HitType.ENTITY, 9, null));
        BulletHitBatchS2CPacket batch = new BulletHitBatchS2CPacket(hits);

        RegistryFriendlyByteBuf buf = buffer();
        BulletHitBatchS2CPacket.STREAM_CODEC.encode(buf, batch);
        buf.readerIndex(0);
        BulletHitBatchS2CPacket out = BulletHitBatchS2CPacket.STREAM_CODEC.decode(buf);

        assertEquals(2, out.hits().size(), "round-trip keeps both hits in the one batch packet");
        assertEquals(1, out.hits().get(0).bulletId());
        assertEquals(12.0, out.hits().get(1).hitX(), 0.0);
    }

    @Test
    void playerBeyondBroadcastRadiusReceivesNoBatch() {
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        List<BulletHitS2CPacket> hits = List.of(
                new BulletHitS2CPacket(1, 0.0, 0.0, 0.0, HitType.BLOCK, BulletHitS2CPacket.NO_ENTITY, null));

        Map<UUID, List<BulletHitS2CPacket>> grouped = BulletHitBroadcastService.groupByNearbyPlayer(
                64.0, hits, List.of(new PlayerPos(near, Level.OVERWORLD, 0.0, 0.0, 0.0),
                        new PlayerPos(far, Level.OVERWORLD, 1000.0, 0.0, 0.0)));

        assertEquals(1, grouped.size(), "only players within radius get a batch");
        assertTrue(grouped.containsKey(near));
        assertFalse(grouped.containsKey(far));
    }

    @Test
    void flushAggregationExcludesPlayersInOtherDimensions() {
        UUID overworldNear = UUID.randomUUID();
        UUID netherNear = UUID.randomUUID();
        List<BulletHitS2CPacket> hits = List.of(
                new BulletHitS2CPacket(1, 0.0, 0.0, 0.0, HitType.BLOCK, BulletHitS2CPacket.NO_ENTITY, null));

        // Real flush seam: dimension filter + proximity grouping. The nether
        // player sits at the hit point (would be "near") but must be excluded
        // because the hit belongs to the overworld dimension.
        Map<UUID, List<BulletHitS2CPacket>> buffers = BulletHitBroadcastService.buildBuffersForLevel(
                Level.OVERWORLD,
                64.0,
                hits,
                List.of(
                        new PlayerPos(overworldNear, Level.OVERWORLD, 0.0, 0.0, 0.0),
                        new PlayerPos(netherNear, Level.NETHER, 0.0, 0.0, 0.0)));

        assertEquals(1, buffers.size(), "only players in the hit's dimension are eligible");
        assertTrue(buffers.containsKey(overworldNear), "overworld player within radius receives the batch");
        assertFalse(buffers.containsKey(netherNear), "nether player never receives an overworld hit, even when near");
    }

    @Test
    void playersInLevelKeepsOnlyMatchingDimension() {
        UUID overworld = UUID.randomUUID();
        UUID nether = UUID.randomUUID();
        UUID end = UUID.randomUUID();

        List<PlayerPos> filtered = BulletHitBroadcastService.playersInLevel(
                Level.NETHER,
                List.of(
                        new PlayerPos(overworld, Level.OVERWORLD, 1.0, 2.0, 3.0),
                        new PlayerPos(nether, Level.NETHER, 4.0, 5.0, 6.0),
                        new PlayerPos(end, Level.END, 7.0, 8.0, 9.0)));

        assertEquals(1, filtered.size(), "only the requested dimension survives the filter");
        assertEquals(nether, filtered.get(0).uuid());
    }

    // ------------------------------------------------------------------
    //  Shoot-animation batching
    // ------------------------------------------------------------------

    @Test
    void multipleSameTickFiresKeepSingleBroadcastWithPeak() {
        ShootAnimSyncService svc = new ShootAnimSyncService();
        UUID playerId = UUID.randomUUID();

        svc.onShootFired(playerId);
        svc.onShootFired(playerId);
        svc.onShootFired(playerId);

        Map<UUID, ShootAnimSyncService.PendingOutbound> pending = svc.drainPendingBroadcasts();
        assertEquals(1, pending.size(), "multiple fires in one tick → exactly one pending broadcast per player");
        ShootAnimSyncService.PendingOutbound out = pending.get(playerId);
        assertEquals(ShootAnimSyncService.SHOOT_ANIM_PEAK, out.shootAnimTimer(), 0.0f,
                "the single broadcast carries the peak animation timer");
        assertTrue(out.isFiring());
    }

    @Test
    void drainClearsPendingSoSecondFlushSendsNothing() {
        ShootAnimSyncService svc = new ShootAnimSyncService();
        UUID playerId = UUID.randomUUID();

        svc.onShootFired(playerId);
        assertEquals(1, svc.drainPendingBroadcasts().size(), "first flush emits the pending broadcast");
        assertTrue(svc.drainPendingBroadcasts().isEmpty(),
                "only one broadcast per tick — the second flush in the same tick sends nothing");
    }

    @Test
    void pendingPeakSurvivesTimerAdvanceInsideSameTick() {
        ShootAnimSyncService svc = new ShootAnimSyncService();
        UUID playerId = UUID.randomUUID();

        svc.onShootFired(playerId);
        svc.advanceTick(playerId); // decay would normally lower the live timer, but the broadcast keeps the peak

        ShootAnimSyncService.PendingOutbound out = svc.drainPendingBroadcasts().get(playerId);
        assertEquals(ShootAnimSyncService.SHOOT_ANIM_PEAK, out.shootAnimTimer(), 0.0f,
                "the tick-end broadcast still carries the peak, not the post-decay value");
    }

    @Test
    void firingTimeoutFlipIsCoalescedToOneBroadcastPerTick() {
        ShootAnimSyncService svc = new ShootAnimSyncService();
        UUID playerId = UUID.randomUUID();

        svc.onShootPacketReceived(playerId); // isFiring = true
        for (int i = 0; i < ShootAnimSyncService.FIRING_TIMEOUT_TICKS; i++) {
            svc.advanceTick(playerId);
        }

        Map<UUID, ShootAnimSyncService.PendingOutbound> pending = svc.drainPendingBroadcasts();
        assertEquals(1, pending.size(), "timeout flip emits a single broadcast per tick");
        assertFalse(pending.get(playerId).isFiring(), "timeout flips the firing flag off in the one broadcast");
    }

    @Test
    void sameTickFireThenTimeoutFlipNeverMergesIntoContradictoryFalsePeak() {
        ShootAnimSyncService svc = new ShootAnimSyncService();
        UUID playerId = UUID.randomUUID();

        // Fire this tick (records the peak) and reach the timeout flip in the
        // same tick's Post phase. The merged single packet must not be a
        // contradictory (isFiring=false, shootAnimTimer=PEAK).
        svc.onShootFired(playerId);
        for (int i = 0; i < ShootAnimSyncService.FIRING_TIMEOUT_TICKS; i++) {
            svc.advanceTick(playerId);
        }

        ShootAnimSyncService.PendingOutbound out = svc.drainPendingBroadcasts().get(playerId);
        assertFalse(out.isFiring(), "timeout flip wins: the merged packet is not firing");
        assertTrue(out.shootAnimTimer() < ShootAnimSyncService.SHOOT_ANIM_PEAK,
                "timeout flip carries the live (decayed) timer, not the stale shoot-tick peak");
    }
}
