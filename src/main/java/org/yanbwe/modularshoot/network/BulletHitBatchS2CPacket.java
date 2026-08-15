package org.yanbwe.modularshoot.network;

import java.util.ArrayList;
import java.util.List;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client batched bullet-hit packet (阶段 2 / 任务 2.4 命中广播聚合).
 *
 * <p>Carries the set of {@link BulletHitS2CPacket}s that occurred during a
 * single server tick for a <em>single recipient player</em>. The server
 * groups all hits of a tick by nearby player and sends one batch payload per
 * player, collapsing the previous one-packet-per-hit stream into a single
 * packet per player per tick while preserving identical client-side semantics
 * (each hit is dispatched through the same effect pipeline as the single
 * packet).</p>
 *
 * <p><b>Wire format:</b> {@code varint} hit-count followed by that many
 * {@link BulletHitS2CPacket} codec payloads, decoded in the same order. The
 * payload id is <em>not</em> written here &mdash; NeoForge writes it
 * automatically around the codec output.</p>
 *
 * @param hits the individual hit results aggregated for one player; never
 *             {@code null}; stored unmodifiable
 */
public record BulletHitBatchS2CPacket(List<BulletHitS2CPacket> hits) implements CustomPacketPayload {

    /** Payload identifier: {@code modularshoot:bullet_hit_batch_s2c}. */
    public static final CustomPacketPayload.Type<BulletHitBatchS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet_hit_batch_s2c"));

    /**
     * Stream codec that serializes the hit list via
     * {@link BulletHitS2CPacket#STREAM_CODEC}.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BulletHitBatchS2CPacket> STREAM_CODEC =
            StreamCodec.of(BulletHitBatchS2CPacket::encode, BulletHitBatchS2CPacket::decode);

    /**
     * Defensive constructor: stores an unmodifiable copy of the hit list so
     * the batch cannot be mutated through the record's accessor.
     *
     * @param hits the hit results to carry; must not be {@code null}
     */
    public BulletHitBatchS2CPacket {
        hits = List.copyOf(hits);
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("a hit batch must carry at least one hit");
        }
    }

    /**
     * Encodes the hit count then each hit via its own codec.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, BulletHitBatchS2CPacket packet) {
        buf.writeVarInt(packet.hits.size());
        for (BulletHitS2CPacket hit : packet.hits) {
            BulletHitS2CPacket.STREAM_CODEC.encode(buf, hit);
        }
    }

    /**
     * Decodes the hit count and then that many individual hit payloads.
     *
     * @param buf the source buffer
     * @return a new {@link BulletHitBatchS2CPacket}
     */
    private static BulletHitBatchS2CPacket decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<BulletHitS2CPacket> hits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            hits.add(BulletHitS2CPacket.STREAM_CODEC.decode(buf));
        }
        return new BulletHitBatchS2CPacket(hits);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
