package org.yanbwe.modularshoot.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless unit tests for the gun-sync state diff (阶段 2 / 任务 2.3):
 * the pure {@link GunStateDiff} helper computes a minimal patch of only the
 * changed state keys, and the client-side merge applies the patch against the
 * existing state. Also covers the {@link GunSyncS2CPacket} state-patch wire
 * mode round trip.
 *
 * <p>Covered behaviours:</p>
 * <ul>
 *   <li>the diff for a {@code (base, target)} pair contains only keys whose
 *       values actually changed;</li>
 *   <li>an unchanged pair produces an empty patch and no removed keys;</li>
 *   <li>the client merge applies the patch keys onto the existing state and
 *       removes the explicitly listed removed keys, leaving the rest
 *       untouched;</li>
 *   <li>a state-patch packet round-trips its patch keys, removed keys and
 *       {@code statePatch} flag intact.</li>
 * </ul>
 */
class GunSyncDiffCodecTest {

    private static CompoundTag state(String kills, String mode) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("kills", kills == null ? 0 : Integer.parseInt(kills));
        tag.putString("mode", mode == null ? "" : mode);
        return tag;
    }

    // ---- pure diff ------------------------------------------------------

    @Test
    void diffContainsOnlyChangedKeys() {
        CompoundTag base = state("3", "full-auto");
        CompoundTag target = state("7", "full-auto"); // only "kills" changed

        CompoundTag patch = GunStateDiff.diff(base, target);

        assertEquals(1, patch.size(),
                "only the changed key belongs in the patch, not the unchanged one");
        assertTrue(patch.contains("kills"), "the changed key is present");
        assertFalse(patch.contains("mode"), "the unchanged key is absent from the patch");
        assertEquals(7, patch.getInt("kills"), "the patch carries the new value");
    }

    @Test
    void unchangedPairProducesEmptyDiff() {
        CompoundTag base = state("3", "full-auto");
        CompoundTag target = state("3", "full-auto");

        assertTrue(GunStateDiff.diff(base, target).isEmpty(),
                "no state change yields an empty patch");
        assertTrue(GunStateDiff.removedKeys(base, target).isEmpty(),
                "no state change yields no removed keys");
    }

    @Test
    void removedKeysAreDetectedWhenKeyDisappears() {
        CompoundTag base = state("3", "full-auto");
        CompoundTag target = new CompoundTag();
        target.putInt("kills", 3); // "mode" removed

        List<String> removed = GunStateDiff.removedKeys(base, target);

        assertEquals(List.of("mode"), removed,
                "a key present in base but absent in target is reported as removed");
    }

    // ---- merge ----------------------------------------------------------

    @Test
    void mergeAppliesChangedKeysOntoExistingState() {
        CompoundTag existing = state("3", "full-auto");
        CompoundTag patch = GunStateDiff.diff(existing, state("9", "semi-auto"));

        CompoundTag merged = GunStateDiff.merge(existing, patch, List.of());

        assertEquals(9, merged.getInt("kills"), "the patched key takes the new value");
        assertEquals("semi-auto", merged.getString("mode"), "the patched key takes the new value");
    }

    @Test
    void mergeRemovesListedKeysLeavingOthersUntouched() {
        CompoundTag existing = state("3", "full-auto");
        existing.putInt("heat", 42);

        CompoundTag merged = GunStateDiff.merge(existing, new CompoundTag(), List.of("mode"));

        assertFalse(merged.contains("mode"), "the listed removed key is gone");
        assertTrue(merged.contains("kills"), "an unlisted key is preserved");
        assertTrue(merged.contains("heat"), "an unlisted key that was not in base-vs-target stays");
    }

    @Test
    void mergeKeepsUnchangedKeysAndReturnsNewTag() {
        CompoundTag existing = state("3", "full-auto");
        existing.putFloat("temp", 0.5f);

        CompoundTag merged = GunStateDiff.merge(existing, new CompoundTag(), List.of());

        assertTrue(merged.contains("temp"), "keys untouched by the patch survive the merge");
        assertEquals(0.5f, merged.getFloat("temp"), 0.0f);
        assertFalse(merged == existing, "merge returns a new tag, not the input");
    }

    // ---- packet state-patch codec --------------------------------------

    @Test
    void statePatchPacketRoundTrips() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);

        CompoundTag patch = new CompoundTag();
        patch.putInt("kills", 9);
        GunSyncS2CPacket packet = GunSyncS2CPacket.statePatch(
                UUID.randomUUID(), 3,
                patch, List.of("mode"));

        GunSyncS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        GunSyncS2CPacket decoded = GunSyncS2CPacket.STREAM_CODEC.decode(buf);

        assertEquals(packet, decoded);
        assertTrue(decoded.statePatch(),
                "the state-patch marker survives the round trip");
        assertEquals(9, decoded.state().getInt("kills"),
                "the patch keys survive the round trip");
        assertEquals(List.of("mode"), decoded.removedStateKeys(),
                "the removed-key list survives the round trip");
    }

    @Test
    void fullStructuralPacketRoundTripsWithEmptyRemovedKeys() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);

        GunSyncS2CPacket.PluginSyncEntry entry = new GunSyncS2CPacket.PluginSyncEntry(
                net.minecraft.resources.ResourceLocation.parse("modularshoot:example_fire"),
                UUID.randomUUID(),
                net.minecraft.resources.ResourceLocation.parse("modularshoot:example_melee"),
                true);
        CompoundTag state = state("3", "full-auto");
        GunSyncS2CPacket packet = GunSyncS2CPacket.full(
                UUID.randomUUID(), 3, List.of(entry), 7, state);

        GunSyncS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        GunSyncS2CPacket decoded = GunSyncS2CPacket.STREAM_CODEC.decode(buf);

        assertEquals(packet, decoded);
        assertFalse(decoded.statePatch(),
                "a structural/full sync is not a state patch");
        assertTrue(decoded.removedStateKeys().isEmpty(),
                "a full sync carries no removed-state-key list");
    }
}
