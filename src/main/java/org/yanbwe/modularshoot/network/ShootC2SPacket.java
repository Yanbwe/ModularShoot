package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server shoot request packet.
 *
 * <p>Sent by the client every tick while the player holds the attack key with
 * a gun in the main hand. The server is the sole authority on whether a shot
 * actually fires — it validates the modifier version (anti-cheat), enforces
 * fire-rate via tick counting, and derives the bullet direction from
 * {@code getLookAngle()} so the client can never tamper with spread or aim.</p>
 *
 * <p><b>Design rationale (see design doc §网络包设计):</b></p>
 * <ul>
 *   <li>The packet carries only {@code modifierVersion} — no timestamp, no
 *       sequence number, no direction. Fire-rate control is driven entirely by
 *       {@link net.minecraft.server.MinecraftServer#getTickCount()} on the
 *       server, so a client cannot gain an advantage by manipulating clocks.</li>
 *   <li>Each packet is stateless and self-contained: a dropped packet simply
 *       means one fewer shot that tick, with no cascading desync.</li>
 *   <li>Bandwidth is negligible (~20 bytes/packet, 20 packets/s).</li>
 * </ul>
 *
 * <p><b>Modifier version anti-cheat:</b> the server compares the packet's
 * version against the main-hand gun's current {@code GunData.modifierVersion}.
 * A mismatch counter tolerates up to 2 consecutive mismatches (network jitter
 * / legitimate plugin install-remove timing); 3 consecutive mismatches trigger
 * a cheat warning and packet rejection without kicking the player.</p>
 *
 * @param modifierVersion the {@code modifierVersion} of the sender's main-hand
 *                        gun at the moment the packet was issued, used for
 *                        anti-cheat validation against the server's value
 */
public record ShootC2SPacket(int modifierVersion) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:shoot_c2s}. */
    public static final CustomPacketPayload.Type<ShootC2SPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "shoot_c2s"));

    /**
     * Stream codec that serializes the single {@code modifierVersion} field
     * using {@link RegistryFriendlyByteBuf#writeInt(int)} / {@link RegistryFriendlyByteBuf#readInt()}.
     *
     * <p>The payload id is <em>not</em> written here — NeoForge writes it
     * automatically around the codec output (see
     * {@link CustomPacketPayload} class docs).</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ShootC2SPacket> STREAM_CODEC =
            StreamCodec.of(ShootC2SPacket::encode, ShootC2SPacket::decode);

    /**
     * Encodes this packet's payload into the buffer.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, ShootC2SPacket packet) {
        buf.writeInt(packet.modifierVersion);
    }

    /**
     * Decodes a packet from the buffer.
     *
     * @param buf the source buffer
     * @return a new {@link ShootC2SPacket} read from the buffer
     */
    private static ShootC2SPacket decode(RegistryFriendlyByteBuf buf) {
        return new ShootC2SPacket(buf.readInt());
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
