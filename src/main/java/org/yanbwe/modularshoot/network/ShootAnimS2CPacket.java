package org.yanbwe.modularshoot.network;

import java.util.UUID;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client shoot animation synchronization packet (设计文档 §第三人称射击动画实现).
 *
 * <p>Sent by the server to nearby clients whenever a player fires a shot or
 * transitions out of the firing state. Receiving clients update their
 * per-player animation state via
 * {@link org.yanbwe.modularshoot.client.PlayerShootStateManager} so the
 * third-person shoot animation (arm recoil) and the {@code while_firing}
 * texture mode can be driven for remote players. This is a pure visual cue;
 * it carries no gameplay state and is safe to drop without desync.</p>
 *
 * <p><b>Fields:</b></p>
 * <ul>
 *   <li>{@code playerUuid} &mdash; the UUID of the player whose animation
 *       state changed. The receiving client uses this to look up (or create)
 *       the per-player state entry.</li>
 *   <li>{@code isFiring} &mdash; whether the player is currently in the
 *       firing state. Drives the {@code while_firing} shoot-texture
 *       selection mode and is independent of the animation timer.</li>
 *   <li>{@code shootAnimTimer} &mdash; the current animation timer value
 *       (in ticks). The receiving client sets the per-player timer to this
 *       value; the timer then decays by 1 per tick on the client until it
 *       reaches 0.</li>
 * </ul>
 *
 * <p><b>Wire format:</b> {@code writeUUID → writeBoolean → writeFloat},
 * decoded in the same order. The payload id is <em>not</em> written here
 * &mdash; NeoForge writes it automatically around the codec output (see
 * {@link CustomPacketPayload} class docs).</p>
 *
 * @param playerUuid     the UUID of the shooting player
 * @param isFiring       whether the player is in the firing state
 * @param shootAnimTimer the animation timer value (in ticks)
 */
public record ShootAnimS2CPacket(UUID playerUuid, boolean isFiring, float shootAnimTimer)
        implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:shoot_anim_s2c}. */
    public static final CustomPacketPayload.Type<ShootAnimS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "shoot_anim_s2c"));

    /**
     * Stream codec that serializes the three fields in order
     * {@code playerUuid → isFiring → shootAnimTimer} and decodes in the same
     * order.
     *
     * <p>The payload id is <em>not</em> written here &mdash; NeoForge writes
     * it automatically around the codec output.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ShootAnimS2CPacket> STREAM_CODEC =
            StreamCodec.of(ShootAnimS2CPacket::encode, ShootAnimS2CPacket::decode);

    /**
     * Encodes this packet's payload into the buffer.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, ShootAnimS2CPacket packet) {
        buf.writeUUID(packet.playerUuid);
        buf.writeBoolean(packet.isFiring);
        buf.writeFloat(packet.shootAnimTimer);
    }

    /**
     * Decodes a packet from the buffer.
     *
     * @param buf the source buffer
     * @return a new {@link ShootAnimS2CPacket} read from the buffer
     */
    private static ShootAnimS2CPacket decode(RegistryFriendlyByteBuf buf) {
        UUID playerUuid = buf.readUUID();
        boolean isFiring = buf.readBoolean();
        float shootAnimTimer = buf.readFloat();
        return new ShootAnimS2CPacket(playerUuid, isFiring, shootAnimTimer);
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
