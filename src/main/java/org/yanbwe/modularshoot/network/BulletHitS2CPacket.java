package org.yanbwe.modularshoot.network;

import org.yanbwe.modularshoot.ModularShoot;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * Server-to-client bullet hit packet (设计文档 §BulletHitS2CPacket).
 *
 * <p>Sent by the server to inform clients that a bullet has impacted
 * something (entity, block, or pierced through a target) so they can play
 * the appropriate impact effect (particles, sound, hit-marker). The server
 * is the sole authority on hit resolution and damage; clients only render
 * the resulting visual feedback and never mutate game state.</p>
 *
 * <p><b>Wire format</b> (order matters — decode reverses encode):</p>
 * <ol>
 *   <li>{@code varint} — bullet id（每维度从 1 起，恒正，1-2 字节）</li>
 *   <li>{@code double} — hit x</li>
 *   <li>{@code double} — hit y</li>
 *   <li>{@code double} — hit z</li>
 *   <li>{@code byte} — {@link HitType} enum ordinal</li>
 *   <li>{@code boolean} has-entity flag + conditional {@code varint} — 实体命中
 *       时为 {@code true} 后跟实体网络 id；非实体命中时仅写 {@code false}（
 *       {@code -1} 哨兵不写线——负数 varint 需 5 字节，比原 {@code int} 还大，
 *       拆成标志后非实体命中 1 字节、实体命中 2-4 字节，两种情形都不劣于
 *       {@code int}）</li>
 *   <li>{@code boolean} presence + {@code ResourceLocation} — 命中音效 ID（未配置时 presence 为 false）</li>
 * </ol>
 *
 * <p>The hit position is transmitted as three primitive {@code double}s
 * rather than a {@code Vec3} so that encode/decode stays a flat sequence of
 * primitive reads/writes with no intermediate object allocation on the
 * hot path.</p>
 *
 * @param bulletId    the id of the bullet that hit, matching the id broadcast
 *                    in {@link BulletS2CPacket} so the client can correlate
 * @param hitX        x-coordinate of the exact hit point in world space
 * @param hitY        y-coordinate of the exact hit point in world space
 * @param hitZ        z-coordinate of the exact hit point in world space
 * @param hitType     kind of hit (ENTITY / BLOCK / PIERCE)
 * @param hitEntityId network id of the hit entity, or {@code -1} when
 *                    {@code hitType != ENTITY}
 * @param soundId    枪械定义 sounds 槽位解析出的命中音效 ID，未配置时为
 *                   null；客户端据此播放数据驱动音效
 */
public record BulletHitS2CPacket(
        int bulletId,
        double hitX,
        double hitY,
        double hitZ,
        HitType hitType,
        int hitEntityId,
        @Nullable ResourceLocation soundId) implements CustomPacketPayload {

    /**
     * Kind of hit a bullet made, used by the client to select the correct
     * impact effect.
     *
     * <p>Encoded on the wire as its {@link Enum#ordinal() enum ordinal} via
     * {@link RegistryFriendlyByteBuf#writeByte(int)}; decoded back with
     * {@code HitType.values()[byte]}. The mod is unreleased, so declaration
     * order is stable and ordinal-based encoding needs no version guard — the
     * decode side trusts the server to never send an out-of-range ordinal.</p>
     */
    public enum HitType {
        /** The bullet struck an entity (damage applied server-side). */
        ENTITY,
        /** The bullet struck a block. */
        BLOCK,
        /** The bullet pierced through a target and continues flying. */
        PIERCE
    }

    /** Sentinel value for {@code hitEntityId} when the hit is not an entity. */
    public static final int NO_ENTITY = -1;

    /** Payload identifier: {@code modularshoot:bullet_hit_s2c}. */
    public static final CustomPacketPayload.Type<BulletHitS2CPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "bullet_hit_s2c"));

    /**
     * Stream codec that serializes the hit-result fields in the order
     * documented in the class Javadoc.
     *
     * <p>The payload id is <em>not</em> written here — NeoForge writes it
     * automatically around the codec output (see
     * {@link CustomPacketPayload} class docs).</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BulletHitS2CPacket> STREAM_CODEC =
            StreamCodec.of(BulletHitS2CPacket::encode, BulletHitS2CPacket::decode);

    /**
     * Encodes this packet's payload into the buffer.
     *
     * @param buf    the target buffer
     * @param packet the packet to serialize
     */
    private static void encode(RegistryFriendlyByteBuf buf, BulletHitS2CPacket packet) {
        // 审查修复: bullet id 恒正（每维度从 1 起）→ varint 1-2 字节；实体 id
        // 用布尔标志 + 条件 varint——负数哨兵（NO_ENTITY = -1）的 varint 编码
        // 是 5 字节（比原 int 还多 1 字节），拆成标志后非实体命中仅 1 字节、
        // 实体命中 2-4 字节，两种情形都不劣于原 int。
        buf.writeVarInt(packet.bulletId);
        buf.writeDouble(packet.hitX);
        buf.writeDouble(packet.hitY);
        buf.writeDouble(packet.hitZ);
        buf.writeByte((byte) packet.hitType.ordinal());
        boolean hasEntity = packet.hitEntityId != NO_ENTITY;
        buf.writeBoolean(hasEntity);
        if (hasEntity) {
            buf.writeVarInt(packet.hitEntityId);
        }
        encodeNullableResourceLocation(buf, packet.soundId);
    }

    /**
     * Decodes a packet from the buffer, reading fields in the reverse order
     * of {@link #encode}.
     *
     * @param buf the source buffer
     * @return a new {@link BulletHitS2CPacket} read from the buffer
     */
    private static BulletHitS2CPacket decode(RegistryFriendlyByteBuf buf) {
        int bulletId = buf.readVarInt();
        double hitX = buf.readDouble();
        double hitY = buf.readDouble();
        double hitZ = buf.readDouble();
        HitType hitType = HitType.values()[buf.readByte()];
        int hitEntityId = buf.readBoolean() ? buf.readVarInt() : NO_ENTITY;
        ResourceLocation soundId = decodeNullableResourceLocation(buf);
        return new BulletHitS2CPacket(bulletId, hitX, hitY, hitZ, hitType, hitEntityId, soundId);
    }

    // --- Nullable ResourceLocation helpers ------------------------------

    /**
     * Writes a nullable {@link ResourceLocation} as a boolean presence flag
     * followed by the location when present.
     *
     * @param buf      the target buffer
     * @param location the location to write, or {@code null}
     */
    private static void encodeNullableResourceLocation(RegistryFriendlyByteBuf buf, @Nullable ResourceLocation location) {
        buf.writeBoolean(location != null);
        if (location != null) {
            buf.writeResourceLocation(location);
        }
    }

    /**
     * Reads a nullable {@link ResourceLocation} written by
     * {@link #encodeNullableResourceLocation}.
     *
     * @param buf the source buffer
     * @return the location, or {@code null} when the presence flag was false
     */
    @Nullable
    private static ResourceLocation decodeNullableResourceLocation(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }

    /**
     * {@return the payload type identifier used by NeoForge to route this packet}
     */
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
