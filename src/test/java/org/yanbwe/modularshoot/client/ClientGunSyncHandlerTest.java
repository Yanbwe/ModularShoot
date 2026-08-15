package org.yanbwe.modularshoot.client;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.network.GunSyncS2CPacket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for {@link ClientGunSyncHandler#rebuildGunData}'s
 * state-patch baseline guard (审查修复, Medium): a state patch must be ignored
 * when no full structural sync baseline has been received yet, so the partial
 * diff is never merged onto potentially-drifted local {@link GunData} state.
 * Once a full baseline has been received, the patch is merged as usual.
 */
class ClientGunSyncHandlerTest {

    private static final UUID GUN = UUID.randomUUID();
    private static final ResourceLocation GUN_ID =
            ResourceLocation.parse("modularshoot:test_rifle");

    @BeforeEach
    @AfterEach
    void reset() {
        ClientGunDataStore.getInstance().clear();
    }

    private static GunData existingWithState(CompoundTag state) {
        return new GunData(GUN_ID, GUN, List.of(), 5, state);
    }

    @Test
    void statePatchWithoutBaselineIsIgnored() {
        CompoundTag base = new CompoundTag();
        base.putInt("kills", 3);
        base.putString("mode", "full-auto");
        GunData existing = existingWithState(base);

        CompoundTag patch = new CompoundTag();
        patch.putInt("kills", 9);
        GunSyncS2CPacket packet =
                GunSyncS2CPacket.statePatch(GUN, 2, patch, List.of("mode"));

        GunData rebuilt = ClientGunSyncHandler.rebuildGunData(existing, packet);

        assertSame(existing, rebuilt,
                "without a full baseline the patch must be ignored and the existing data returned unchanged");
        assertEquals(3, rebuilt.state().getInt("kills"),
                "the patch key must not be merged without a baseline");
        assertTrue(rebuilt.state().contains("mode"),
                "the removed key must not be applied without a baseline");
    }

    @Test
    void statePatchAppliesAfterFullBaseline() {
        // Establish a full structural baseline in the sync channel first.
        CompoundTag fullState = new CompoundTag();
        fullState.putInt("kills", 3);
        fullState.putString("mode", "full-auto");
        ClientGunDataStore.getInstance().handleSync(
                GunSyncS2CPacket.full(GUN, 2, List.of(), 7, fullState));
        assertTrue(ClientGunDataStore.getInstance().hasSyncData(),
                "a full sync establishes the baseline");

        GunData existing = existingWithState(fullState);

        CompoundTag patch = new CompoundTag();
        patch.putInt("kills", 9);
        GunSyncS2CPacket packet =
                GunSyncS2CPacket.statePatch(GUN, 2, patch, List.of("mode"));

        GunData rebuilt = ClientGunSyncHandler.rebuildGunData(existing, packet);

        assertFalse(rebuilt == existing,
                "once a baseline exists the patch produces a rebuilt data object");
        assertEquals(9, rebuilt.state().getInt("kills"),
                "the patch overwrites the changed key once a baseline exists");
        assertFalse(rebuilt.state().contains("mode"),
                "the patch's removed key is applied once a baseline exists");
    }
}
