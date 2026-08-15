package org.yanbwe.modularshoot.client;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link ClientGunDataStore}'s sync handling
 * (审查修复, Low): a {@code statePatch} must be ignored when no full structural
 * baseline has been received yet, so partial patches are never merged onto an
 * empty state.
 */
class ClientGunDataStoreTest {

    private final UUID GUN = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void reset() {
        ClientGunDataStore.getInstance().clear();
    }

    @Test
    void statePatchWithoutBaselineIsIgnored() {
        ClientGunDataStore store = ClientGunDataStore.getInstance();

        CompoundTag patch = new CompoundTag();
        patch.putInt("kills", 9);
        store.handleSync(GunSyncS2CPacket.statePatch(GUN, 2, patch, List.of("mode")));

        assertFalse(store.hasSyncData(),
                "a state patch must not establish sync data without a full baseline");
        assertTrue(store.getState().isEmpty(),
                "the patch must not be merged onto an empty state");
    }

    @Test
    void fullSyncThenPatchMergesCorrectly() {
        ClientGunDataStore store = ClientGunDataStore.getInstance();

        CompoundTag fullState = new CompoundTag();
        fullState.putInt("kills", 3);
        fullState.putString("mode", "full-auto");
        store.handleSync(GunSyncS2CPacket.full(GUN, 2, List.of(), 7, fullState));
        assertTrue(store.hasSyncData(), "a full sync establishes baseline");

        CompoundTag patch = new CompoundTag();
        patch.putInt("kills", 9);
        store.handleSync(GunSyncS2CPacket.statePatch(GUN, 2, patch, List.of("mode")));

        assertEquals(9, store.getState().getInt("kills"),
                "the patch overwrites the changed key once a baseline exists");
        assertFalse(store.getState().contains("mode"),
                "the patch's removed key is applied once a baseline exists");
    }
}
