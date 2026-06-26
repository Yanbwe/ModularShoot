package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server reload request packet.
 *
 * <p>Sent by the client when the player single-presses the reload key
 * (default: R) while holding a gun in the main hand. The packet carries
 * <strong>no payload</strong> — it is a pure signal. The server derives
 * everything it needs from the sender's own state: the player is obtained
 * from the packet context, and the gun is read from the player's main hand.
 * This keeps the protocol minimal and prevents the client from spoofing a
 * different gun stack.</p>
 *
 * <p><b>Design rationale (see design doc §ReloadEvent):</b></p>
 * <ul>
 *   <li>The server is the sole authority — it re-checks
 *       {@link org.yanbwe.modularshoot.ModularShootAPI#isGun(ItemStack)}
 *       on the main-hand item before firing
 *       {@link org.yanbwe.modularshoot.api.event.ReloadEvent}, so a hacked
 *       client cannot trigger a reload event for a non-gun item.</li>
 *   <li>The packet is stateless and idempotent: a dropped packet simply
 *       means no reload request that tick, with no cascading desync.</li>
 *   <li>Bandwidth is negligible (only the payload id header, ~4 bytes).</li>
 * </ul>
 *
 * <p><b>Wire format:</b> empty. The {@link #STREAM_CODEC} uses
 * {@link StreamCodec#unit(Object)} which reads and writes nothing — the
 * payload id is written automatically by NeoForge around the codec output
 * (see {@link CustomPacketPayload} class docs).</p>
 *
 * @see ShootC2SPacket for the C→S packet pattern with a non-empty payload
 */
public record ReloadC2SPacket() implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:reload_c2s}. */
    public static final CustomPacketPayload.Type<ReloadC2SPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "reload_c2s"));

    /**
     * Stream codec for the empty payload.
     *
     * <p>Uses {@link StreamCodec#unit(Object)} which performs no buffer
     * reads or writes — every decoded packet is the same singleton-like
     * instance. The payload id is <em>not</em> written here; NeoForge
     * writes it automatically around the codec output.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ReloadC2SPacket> STREAM_CODEC =
            StreamCodec.unit(new ReloadC2SPacket());

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
