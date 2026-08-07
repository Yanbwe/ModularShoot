package org.yanbwe.modularshoot.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip test for the {@link GunSyncS2CPacket} stream codec: the wire
 * format must preserve every field, including the {@code gunInstanceUuid}
 * ownership marker added for sync-snapshot validation.
 */
class GunSyncS2CPacketCodecTest {

    @Test
    void roundTripPreservesAllFields() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);

        UUID gunUuid = UUID.randomUUID();
        UUID pluginUuid = UUID.randomUUID();
        GunSyncS2CPacket.PluginSyncEntry entry = new GunSyncS2CPacket.PluginSyncEntry(
                ResourceLocation.parse("modularshoot:example_fire"), pluginUuid,
                ResourceLocation.parse("modularshoot:example_melee"), true);
        CompoundTag state = new CompoundTag();
        state.putInt("kills", 3);
        state.putString("mode", "full-auto");

        GunSyncS2CPacket packet = new GunSyncS2CPacket(gunUuid, 3, List.of(entry), 7, state);
        GunSyncS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        GunSyncS2CPacket decoded = GunSyncS2CPacket.STREAM_CODEC.decode(buf);

        assertEquals(packet, decoded);
    }

    @Test
    void roundTripPreservesEmptyPluginListAndEmptyState() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);

        GunSyncS2CPacket packet = new GunSyncS2CPacket(UUID.randomUUID(), 7, List.of(), 0, new CompoundTag());
        GunSyncS2CPacket.STREAM_CODEC.encode(buf, packet);
        buf.readerIndex(0);
        GunSyncS2CPacket decoded = GunSyncS2CPacket.STREAM_CODEC.decode(buf);

        assertEquals(packet, decoded);
    }
}
