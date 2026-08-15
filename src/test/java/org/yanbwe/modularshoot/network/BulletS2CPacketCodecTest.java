package org.yanbwe.modularshoot.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletS2CPacket.DeltaBulletEntry;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry;
import org.yanbwe.modularshoot.network.BulletS2CPacket.FullBulletEntry.LayerEntryFull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the {@link BulletS2CPacket} stream codec — the most
 * hand-rolled wire format in the sync layer (three entry buckets +
 * force-full-sync flag, nullable locations/tint/uuid, variable-length layer
 * lists). Every test encodes into a fresh {@link RegistryFriendlyByteBuf}
 * and decodes from the same buffer, then asserts field-by-field equality,
 * including the content-addressed style semantics (阶段 2 / 任务 2.3):
 * a {@link FullBulletEntry} carries a style wire id and an optional full
 * {@link BulletStyleData} payload; the {@code null} sentinel for the style
 * payload must survive the round trip.
 */
class BulletS2CPacketCodecTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);
    }

    private static BulletS2CPacket roundTrip(BulletS2CPacket packet) {
        RegistryFriendlyByteBuf buf = buffer();
        BulletS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        return BulletS2CPacket.STREAM_CODEC.decode(buf);
    }

    private static ClientBulletSnapshot snapshot() {
        return new ClientBulletSnapshot(
                Map.of(
                        ResourceLocation.parse("modularshoot:hit_damage"), 6.0,
                        ResourceLocation.parse("modularshoot:fire_rate"), 4.0),
                Map.of(ResourceLocation.parse("modularshoot:example_flame"), true),
                ResourceLocation.parse("modularshoot:composite_cane"),
                UUID.fromString("6e8a2f4a-1b2c-3d4e-5f6a-7b8c9d0e1f2a"));
    }

    /** A billboard-mode additive layer (texture path, no model). */
    private static LayerEntryFull billboardLayer() {
        return new LayerEntryFull(
                "billboard",
                ResourceLocation.parse("modularshoot:textures/bullet/flame.png"),
                null,
                true, false,
                0.1f, -0.2f, 0.3f,
                1.5f,
                0.5f, 0.6f, 0.7f, 1.0f);
    }

    /** A 3d-mode additive layer (model path, no texture). */
    private static LayerEntryFull threeDLayer() {
        return new LayerEntryFull(
                "3d",
                null,
                ResourceLocation.parse("modularshoot:item/ice_shard"),
                false, true,
                -1.0f, 2.0f, -3.0f,
                0.25f,
                1.0f, 0.9f, 0.8f, 0.5f);
    }

    private static BulletStyleData styleData() {
        return new BulletStyleData(
                ResourceLocation.parse("modularshoot:textures/bullet/example.png"),
                ResourceLocation.parse("modularshoot:item/example"),
                "3d",
                0.75f,
                new Vector4f(0.1f, 0.2f, 0.3f, 0.9f),
                List.of(billboardLayer(), threeDLayer()),
                snapshot());
    }

    /** A full entry carrying a full style payload (first transmission). */
    private static FullBulletEntry fullEntryWithStyle(int bulletId, int styleId) {
        return new FullBulletEntry(
                bulletId,
                1.5, 2.5, 3.5,
                0.0, 1.0, 0.0,
                42,
                styleId,
                styleData());
    }

    /** A full entry referencing a style id the client already has (no payload). */
    private static FullBulletEntry fullEntryStyleRef(int bulletId, int styleId) {
        return new FullBulletEntry(
                bulletId,
                1.5, 2.5, 3.5,
                0.0, 1.0, 0.0,
                42,
                styleId,
                null);
    }

    // --- FullBulletEntry ---

    @Test
    void roundTripPreservesEveryFieldOfFullBulletEntryWithStyle() {
        FullBulletEntry entry = fullEntryWithStyle(17, 3);
        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        assertEquals(1, decoded.newBullets().size());
        FullBulletEntry out = decoded.newBullets().get(0);
        assertEquals(17, out.bulletId());
        assertEquals(1.5, out.posX(), 0.0);
        assertEquals(2.5, out.posY(), 0.0);
        assertEquals(3.5, out.posZ(), 0.0);
        assertEquals(0.0, out.dirX(), 0.0);
        assertEquals(1.0, out.dirY(), 0.0);
        assertEquals(0.0, out.dirZ(), 0.0);
        assertEquals(42, out.shooterEntityId());
        assertEquals(3, out.styleId(), "the content-addressed style wire id round-trips");
        assertNotNull(out.style(), "a first-transmission entry carries the full style payload");
        assertEquals(styleData().texture(), out.style().texture());
        assertEquals(styleData().modelLocation(), out.style().modelLocation());
        assertEquals("3d", out.style().renderMode(),
                "render mode tag survives the enum-ordinal wire encoding");
        assertEquals(0.75f, out.style().renderScale(), 0.0f);
        assertEquals(styleData().composedTint(), out.style().composedTint());
        assertEquals(styleData().snapshot(), out.style().snapshot(),
                "ClientBulletSnapshot projection (stats/traits/gunId/shooter) round-trips intact");
        assertEquals(2, out.style().layers().size(),
                "variable-length layer list round-trips in source order");
        assertEquals(styleData().layers(), out.style().layers());
    }

    @Test
    void roundTripPreservesNullStyleSentinelForKnownStyle() {
        FullBulletEntry entry = fullEntryStyleRef(9, 4);
        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        FullBulletEntry out = decoded.newBullets().get(0);
        assertEquals(4, out.styleId());
        assertNull(out.style(),
                "a style-less entry (client already has the id) stays null on the wire");
    }

    @Test
    void roundTripPreservesNullLocationsAndOwnerlessSnapshot() {
        BulletStyleData ownerless = new BulletStyleData(
                null, // no billboard texture
                null, // no 3d model
                "3d",
                0.5f,
                new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                List.of(),
                new ClientBulletSnapshot(
                        Map.of(ResourceLocation.parse("modularshoot:hit_damage"), 3.0),
                        Map.of(),
                        null, // independent firing has no gun id
                        null) // ownerless shooter uuid
        );
        FullBulletEntry entry = new FullBulletEntry(
                2, -10.0, 64.0, 200.0, 0.0, 0.0, -1.0, -1, 7, ownerless);

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        BulletStyleData out = decoded.newBullets().get(0).style();
        assertNull(out.texture(), "null texture must survive as null");
        assertNull(out.modelLocation(), "null modelLocation must survive as null");
        assertNull(out.snapshot().gunId(), "null snapshot gunId must survive as null");
        assertNull(out.snapshot().shooter(), "null snapshot shooter uuid must survive as null");
        assertTrue(out.layers().isEmpty(), "empty layer list survives");
    }

    @Test
    void roundTripPreservesBillboardRenderModeTag() {
        BulletStyleData billboard = new BulletStyleData(
                ResourceLocation.parse("modularshoot:textures/bullet/example.png"),
                null,
                "billboard",
                1.0f,
                null, // white identity sentinel
                List.of(),
                new ClientBulletSnapshot(Map.of(), Map.of(), null, null));
        FullBulletEntry entry = new FullBulletEntry(
                3, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, -1, 1, billboard);

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        assertEquals("billboard", decoded.newBullets().get(0).style().renderMode(),
                "billboard (ordinal 0) render-mode tag survives the wire");
        assertNull(decoded.newBullets().get(0).style().composedTint(),
                "null (white identity) composed tint sentinel survives the wire");
    }

    // --- DeltaBulletEntry ---

    @Test
    void roundTripPreservesDeltaBulletEntryPositionAndDirection() {
        DeltaBulletEntry delta = new DeltaBulletEntry(
                17, 10.25, 11.5, 12.75, 0.25, 0.5, 0.75);

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(), List.of(delta), List.of()));

        assertEquals(1, decoded.updatedBullets().size());
        assertEquals(delta, decoded.updatedBullets().get(0),
                "delta entry carries id plus absolute position/direction");
    }

    @Test
    void deltaFloatCompressionKeepsSubPixelPrecision() {
        // 审查优化 P3: delta 条目在线路上以 float 传输（52 → ~26 字节）。
        DeltaBulletEntry delta = new DeltaBulletEntry(
                1001, 1000.1, 64.7, -200.3, 0.1, 0.2, -0.3);

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(), List.of(delta), List.of()));

        DeltaBulletEntry out = decoded.updatedBullets().get(0);
        assertEquals(1001, out.bulletId());
        assertEquals(delta.posX(), out.posX(), 1.0e-4);
        assertEquals(delta.posY(), out.posY(), 1.0e-4);
        assertEquals(delta.posZ(), out.posZ(), 1.0e-4);
        assertEquals(delta.dirX(), out.dirX(), 1.0e-4);
        assertEquals(delta.dirY(), out.dirY(), 1.0e-4);
        assertEquals(delta.dirZ(), out.dirZ(), 1.0e-4);
    }

    // --- removedBulletIds ---

    @Test
    void roundTripPreservesRemovedBulletIdList() {
        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(), List.of(), List.of(11, 22, 33)));

        assertEquals(List.of(11, 22, 33), decoded.removedBulletIds(),
                "removed-bullet id list round-trips in order");
    }

    // --- empty packet ---

    @Test
    void roundTripOfEmptyPacketDecodesToEmptyBuckets() {
        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(), List.of(), List.of()));

        assertTrue(decoded.newBullets().isEmpty());
        assertTrue(decoded.updatedBullets().isEmpty());
        assertTrue(decoded.removedBulletIds().isEmpty());
        assertFalse(decoded.forceFullSync(),
                "empty incremental delta is not a force-full-sync");
    }

    // --- forceFullSync flag ---

    @Test
    void roundTripPreservesForceFullSyncFlag() {
        BulletS2CPacket packet = BulletS2CPacket.fullSync(
                List.of(fullEntryWithStyle(1, 1), fullEntryWithStyle(2, 1)));

        BulletS2CPacket decoded = roundTrip(packet);

        assertTrue(decoded.forceFullSync(),
                "force-full-sync flag survives the round trip (reconcile semantics)");
        assertEquals(2, decoded.newBullets().size(),
                "full-sync bucket carries the complete bullet set");
        assertTrue(decoded.updatedBullets().isEmpty(), "full-sync ignores delta bucket");
        assertTrue(decoded.removedBulletIds().isEmpty(), "full-sync ignores removal bucket");
    }

    // --- defensive render-mode decode (阶段 7 / 任务 7.1) -----------------

    @Test
    void decodeRenderModeMapsKnownOrdinalsToSerializedNames() {
        assertEquals("billboard", BulletS2CPacket.decodeRenderMode((byte) 0),
                "ordinal 0 is BILLBOARD");
        assertEquals("3d", BulletS2CPacket.decodeRenderMode((byte) 1),
                "ordinal 1 is THREE_D");
    }

    @Test
    void decodeRenderModeFallsBackToBillboardForOutOfRangeOrdinals() {
        // Previously a direct values()[byte] index would throw
        // ArrayIndexOutOfBoundsException on these malformed inputs.
        assertEquals("billboard", BulletS2CPacket.decodeRenderMode((byte) -1),
                "negative ordinal falls back to billboard");
        assertEquals("billboard", BulletS2CPacket.decodeRenderMode((byte) 99),
                "over-range ordinal falls back to billboard");
        assertEquals("billboard", BulletS2CPacket.decodeRenderMode(Byte.MAX_VALUE),
                "max byte ordinal falls back to billboard");
    }

    @Test
    void decodeRenderModeNeverThrowsForAnyByteValue() {
        // Exhaustive sweep over every byte: the decode helper must map every
        // possible wire value without throwing (defensive robustness). Valid
        // ordinals keep their name; out-of-range values fall back to billboard.
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            if (b == 0) {
                assertEquals("billboard", BulletS2CPacket.decodeRenderMode(b));
            } else if (b == 1) {
                assertEquals("3d", BulletS2CPacket.decodeRenderMode(b));
            } else {
                assertEquals("billboard", BulletS2CPacket.decodeRenderMode(b),
                        "out-of-range byte " + b + " must fall back to billboard");
            }
        }
    }
}
