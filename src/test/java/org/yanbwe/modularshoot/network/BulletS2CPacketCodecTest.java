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
 * and decodes from the same buffer (bootstrap recipe identical to
 * {@link GunSyncS2CPacketCodecTest}), then asserts field-by-field equality,
 * including the {@code null} sentinel semantics: {@code null} texture /
 * model / gunId / shooter / composedTint must survive the round trip as
 * {@code null}.
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

    private static FullBulletEntry fullEntry(int bulletId) {
        return new FullBulletEntry(
                bulletId,
                1.5, 2.5, 3.5,
                0.0, 1.0, 0.0,
                ResourceLocation.parse("modularshoot:textures/bullet/example.png"),
                ResourceLocation.parse("modularshoot:item/example"),
                "3d",
                0.75f,
                42,
                snapshot(),
                new Vector4f(0.1f, 0.2f, 0.3f, 0.9f),
                List.of(billboardLayer(), threeDLayer()));
    }

    // --- FullBulletEntry ---

    @Test
    void roundTripPreservesEveryFieldOfFullBulletEntry() {
        FullBulletEntry entry = fullEntry(17);
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
        assertEquals(ResourceLocation.parse("modularshoot:textures/bullet/example.png"), out.texture());
        assertEquals(ResourceLocation.parse("modularshoot:item/example"), out.modelLocation());
        assertEquals("3d", out.renderMode(), "render mode tag survives the enum-ordinal wire encoding");
        assertEquals(0.75f, out.renderScale(), 0.0f);
        assertEquals(42, out.shooterEntityId());
        assertEquals(entry.snapshot(), out.snapshot(),
                "ClientBulletSnapshot projection (stats/traits/gunId/shooter) round-trips intact");
        assertEquals(entry.composedTint(), out.composedTint(),
                "non-null composed tint keeps its four channels");
        assertEquals(2, out.layers().size(), "variable-length layer list round-trips in source order");
        assertEquals(entry.layers(), out.layers(),
                "each layer keeps mode, texture/model, follow flags, offset, scale and tint");
    }

    @Test
    void roundTripPreservesNullSentinelForComposedTint() {
        FullBulletEntry entry = new FullBulletEntry(
                1,
                0.0, 0.0, 0.0,
                1.0, 0.0, 0.0,
                ResourceLocation.parse("modularshoot:textures/bullet/example.png"),
                null,
                "billboard",
                1.0f,
                -1,
                snapshot(),
                null, // null sentinel: client rebuilds the white identity tint (1,1,1,1)
                List.of());

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        assertNull(decoded.newBullets().get(0).composedTint(),
                "null composedTint must stay null on the wire (it is the white-identity sentinel)");
    }

    @Test
    void roundTripPreservesNullLocationsAndOwnerlessSnapshot() {
        FullBulletEntry entry = new FullBulletEntry(
                2,
                -10.0, 64.0, 200.0,
                0.0, 0.0, -1.0,
                null, // no billboard texture
                null, // no 3d model
                "3d",
                0.5f,
                -1,   // ownerless (independent firing, e.g. traps)
                new ClientBulletSnapshot(
                        Map.of(ResourceLocation.parse("modularshoot:hit_damage"), 3.0),
                        Map.of(),
                        null, // independent firing has no gun id
                        null), // ownerless shooter uuid
                new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                List.of());

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        FullBulletEntry out = decoded.newBullets().get(0);
        assertNull(out.texture(), "null texture must survive as null");
        assertNull(out.modelLocation(), "null modelLocation must survive as null");
        assertEquals(-1, out.shooterEntityId(), "ownerless shooter marker survives");
        assertNull(out.snapshot().gunId(), "null snapshot gunId must survive as null");
        assertNull(out.snapshot().shooter(), "null snapshot shooter uuid must survive as null");
        assertTrue(out.layers().isEmpty(), "empty layer list survives");
    }

    @Test
    void roundTripPreservesBillboardRenderModeTag() {
        FullBulletEntry entry = new FullBulletEntry(
                3,
                0.0, 0.0, 0.0,
                0.0, 0.0, 1.0,
                ResourceLocation.parse("modularshoot:textures/bullet/example.png"),
                null,
                "billboard",
                1.0f,
                -1,
                new ClientBulletSnapshot(Map.of(), Map.of(), null, null),
                null,
                List.of());

        BulletS2CPacket decoded = roundTrip(
                BulletS2CPacket.delta(List.of(entry), List.of(), List.of()));

        assertEquals("billboard", decoded.newBullets().get(0).renderMode(),
                "billboard (ordinal 0) render-mode tag survives the wire");
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
        // float 不能精确表示 0.1，往返后允许亚像素级偏差（渲染不可见），
        // 但必须远小于 1 像素（≈0.06 方块，y=1000 处 float 精度）。
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
        BulletS2CPacket packet = BulletS2CPacket.fullSync(List.of(fullEntry(1), fullEntry(2)));

        BulletS2CPacket decoded = roundTrip(packet);

        assertTrue(decoded.forceFullSync(),
                "force-full-sync flag survives the round trip (reconcile semantics)");
        assertEquals(2, decoded.newBullets().size(),
                "full-sync bucket carries the complete bullet set");
        assertTrue(decoded.updatedBullets().isEmpty(), "full-sync ignores delta bucket");
        assertTrue(decoded.removedBulletIds().isEmpty(), "full-sync ignores removal bucket");
    }
}
