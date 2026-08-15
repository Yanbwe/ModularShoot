package org.yanbwe.modularshoot.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.GunSyncService.PlayerStateKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GunSyncService#pruneStaleBaselines} (阶段 7 /
 * 任务 7.1 — 审查 Medium 1): recording a structural full sync must drop the
 * same player's other gun-uuid baselines while keeping the current gun and
 * every other player's baselines.
 *
 * <p>The seam is a pure map operation (package-private), so it is tested
 * headlessly without a {@link ServerPlayer} (the real {@code recordSyncedState}
 * requires an entity harness).</p>
 */
class GunSyncServicePruneTest {

    private static final UUID PLAYER_A = UUID.randomUUID();
    private static final UUID PLAYER_B = UUID.randomUUID();
    private static final UUID GUN_1 = UUID.randomUUID();
    private static final UUID GUN_2 = UUID.randomUUID();
    private static final UUID GUN_3 = UUID.randomUUID();

    @Test
    void pruneRemovesSamePlayersOtherGunsOnly() {
        Map<GunSyncService.PlayerStateKey, CompoundTag> map = new ConcurrentHashMap<>();
        map.put(new PlayerStateKey(PLAYER_A, GUN_1), new CompoundTag());
        map.put(new PlayerStateKey(PLAYER_A, GUN_2), new CompoundTag());
        map.put(new PlayerStateKey(PLAYER_B, GUN_3), new CompoundTag());

        // Player A switches to GUN_2: the GUN_1 baseline is stale, GUN_2 and
        // player B's entries must survive.
        GunSyncService.pruneStaleBaselines(map, PLAYER_A, GUN_2);

        assertEquals(2, map.size(), "only player A's stale gun baseline is removed");
        assertFalse(map.containsKey(new PlayerStateKey(PLAYER_A, GUN_1)),
                "player A's other gun (GUN_1) baseline must be pruned");
        assertTrue(map.containsKey(new PlayerStateKey(PLAYER_A, GUN_2)),
                "player A's current gun (GUN_2) baseline must be kept");
        assertTrue(map.containsKey(new PlayerStateKey(PLAYER_B, GUN_3)),
                "another player's baseline must never be pruned");
    }

    @Test
    void pruneKeepsOnlyCurrentGunWhenPlayerHoldsMany() {
        Map<GunSyncService.PlayerStateKey, CompoundTag> map = new ConcurrentHashMap<>();
        map.put(new PlayerStateKey(PLAYER_A, GUN_1), new CompoundTag());
        map.put(new PlayerStateKey(PLAYER_A, GUN_2), new CompoundTag());
        map.put(new PlayerStateKey(PLAYER_A, GUN_3), new CompoundTag());

        GunSyncService.pruneStaleBaselines(map, PLAYER_A, GUN_3);

        assertEquals(1, map.size(), "all of player A's other gun baselines are pruned");
        assertTrue(map.containsKey(new PlayerStateKey(PLAYER_A, GUN_3)),
                "the current main-hand gun baseline is the only survivor");
    }

    @Test
    void pruneIsNoOpWhenNoOtherGunForPlayer() {
        Map<GunSyncService.PlayerStateKey, CompoundTag> map = new ConcurrentHashMap<>();
        map.put(new PlayerStateKey(PLAYER_A, GUN_1), new CompoundTag());
        map.put(new PlayerStateKey(PLAYER_B, GUN_2), new CompoundTag());

        GunSyncService.pruneStaleBaselines(map, PLAYER_A, GUN_1);

        assertEquals(2, map.size(), "no stale baseline exists → nothing is pruned");
    }
}
