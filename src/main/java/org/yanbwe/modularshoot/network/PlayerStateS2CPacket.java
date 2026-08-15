package org.yanbwe.modularshoot.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.state.PlayerStateData;

/**
 * Server-to-client per-player state sync packet (任务 3.3 — PlayerState 同步节流).
 *
 * <p>Carries the authoritative {@link PlayerStateData} of a player to that
 * player's own client. Sent by
 * {@link PlayerStateSyncService} at a throttled cadence (at most once every
 * {@link org.yanbwe.modularshoot.state.PlayerStateThrottleManager#THROTTLE_INTERVAL_TICKS}
 * ticks) instead of NeoForge's per-{@code setData} attachment auto-sync, whose
 * auto-sync handler was removed from
 * {@link org.yanbwe.modularshoot.state.ModularShootAttachmentTypes#PLAYER_STATE}.</p>
 *
 * <p>The client handler applies the payload by calling
 * {@code player.setData(PLAYER_STATE, data)}, so the local
 * {@link org.yanbwe.modularshoot.state.PlayerState} reads converge to the
 * server's eventually-consistent state.</p>
 *
 * <p><b>Wire format:</b> reuses {@link PlayerStateData#STREAM_CODEC} so the
 * on-the-wire representation is identical to what the attachment previously
 * used; only the payload id differs.</p>
 *
 * @param data the full per-player state payload to install on the client
 */
public record PlayerStateS2CPacket(PlayerStateData data) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:player_state_s2c}. */
    public static final CustomPacketPayload.Type<PlayerStateS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "player_state_s2c"));

    /**
     * Stream codec delegating to {@link PlayerStateData#STREAM_CODEC}.
     *
     * <p>The payload id is written by NeoForge, not here.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerStateS2CPacket> STREAM_CODEC =
            StreamCodec.composite(
                    PlayerStateData.STREAM_CODEC,
                    PlayerStateS2CPacket::data,
                    PlayerStateS2CPacket::new);

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
