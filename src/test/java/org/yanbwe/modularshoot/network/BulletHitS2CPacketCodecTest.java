package org.yanbwe.modularshoot.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip tests for the {@link BulletHitS2CPacket} stream codec
 * (审查修复: 实体 id 的布尔标志 + 条件 varint 线格式).
 *
 * <p>The wire format writes the entity id as a presence flag plus a
 * conditional varint — never a raw varint of the {@code -1} sentinel (a
 * negative varint costs 5 bytes, worse than the old {@code int}). These
 * tests pin both branches: entity hits keep their positive id, non-entity
 * hits restore the {@link BulletHitS2CPacket#NO_ENTITY} sentinel, and the
 * nullable sound id survives in both directions.</p>
 */
class BulletHitS2CPacketCodecTest {

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);
    }

    private static BulletHitS2CPacket roundTrip(BulletHitS2CPacket packet) {
        RegistryFriendlyByteBuf buf = buffer();
        BulletHitS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        return BulletHitS2CPacket.STREAM_CODEC.decode(buf);
    }

    @Test
    void roundTripEntityHitKeepsPositiveEntityId() {
        BulletHitS2CPacket packet = new BulletHitS2CPacket(
                1001, 12.5, 64.0, -30.25, HitType.ENTITY, 424242,
                ResourceLocation.parse("modularshoot:hit_entity"));

        BulletHitS2CPacket out = roundTrip(packet);

        assertEquals(1001, out.bulletId());
        assertEquals(12.5, out.hitX(), 0.0);
        assertEquals(64.0, out.hitY(), 0.0);
        assertEquals(-30.25, out.hitZ(), 0.0);
        assertEquals(HitType.ENTITY, out.hitType());
        assertEquals(424242, out.hitEntityId(), "positive entity id survives the conditional varint");
        assertEquals(ResourceLocation.parse("modularshoot:hit_entity"), out.soundId());
    }

    @Test
    void roundTripNonEntityHitRestoresNoEntitySentinel() {
        BulletHitS2CPacket packet = new BulletHitS2CPacket(
                1002, 1.0, 2.0, 3.0, HitType.BLOCK, BulletHitS2CPacket.NO_ENTITY, null);

        BulletHitS2CPacket out = roundTrip(packet);

        assertEquals(1002, out.bulletId());
        assertEquals(HitType.BLOCK, out.hitType());
        assertEquals(BulletHitS2CPacket.NO_ENTITY, out.hitEntityId(),
                "the -1 sentinel is not written as a varint; decode restores it from the absent flag");
        assertNull(out.soundId(), "null sound id survives as null");
    }

    @Test
    void roundTripPierceHit() {
        BulletHitS2CPacket packet = new BulletHitS2CPacket(
                1003, -8.5, 100.0, 512.75, HitType.PIERCE, 77, null);

        BulletHitS2CPacket out = roundTrip(packet);

        assertEquals(1003, out.bulletId());
        assertEquals(HitType.PIERCE, out.hitType());
        assertEquals(77, out.hitEntityId());
        assertNull(out.soundId());
    }
}
