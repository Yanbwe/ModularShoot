package org.yanbwe.modularshoot.client.event;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;

/**
 * 客户端子弹命中事件（设计文档 §音效系统 — 命中特效钩子）。
 *
 * <p>客户端收到 {@code BulletHitS2CPacket} 时、在播放默认命中音效之前 post
 * 到 {@code NeoForge.EVENT_BUS}。框架默认不生成任何命中粒子——视觉特效完全
 * 交给监听方。扩展模组监听本事件可：</p>
 * <ul>
 *   <li><b>取消</b>（{@link ICancellableEvent}）— 跳过默认音效，
 *       完全接管本次命中反馈；</li>
 *   <li><b>不取消</b> — 默认音效照常播放，监听方可在自身逻辑中追加
 *       自定义音效/粒子。</li>
 * </ul>
 *
 * <p>{@link #getSoundId()} 为服务端从枪械定义 {@code sounds} 槽位解析出的
 * 命中音效 ID（实体 {@code hit_entity} / 方块 {@code hit_block} / 穿透
 * {@code hit_pierce}）；枪械未配置该槽位时为 {@code null}（默认静音）。
 * 监听方也可忽略该 ID 自行选择音效。</p>
 *
 * <p>仅在物理客户端触发（数据包为 playToClient），不会在服务端出现。</p>
 */
public class ClientBulletHitEvent extends Event implements ICancellableEvent {
    private final ClientLevel level;
    private final Vec3 hitPos;
    private final HitType hitType;
    private final int hitEntityId;
    @Nullable
    private final ResourceLocation soundId;

    /**
     * @param level       客户端世界
     * @param hitPos      世界空间命中点
     * @param hitType     命中类型（ENTITY / BLOCK / PIERCE）
     * @param hitEntityId 被命中实体的网络 ID；非实体命中时为
     *                    {@code BulletHitS2CPacket.NO_ENTITY}（-1）
     * @param soundId     枪械 sounds 槽位解析的命中音效 ID；未配置时为 null
     */
    public ClientBulletHitEvent(ClientLevel level, Vec3 hitPos, HitType hitType,
                                int hitEntityId, @Nullable ResourceLocation soundId) {
        this.level = level;
        this.hitPos = hitPos;
        this.hitType = hitType;
        this.hitEntityId = hitEntityId;
        this.soundId = soundId;
    }

    /** @return 客户端世界，用于粒子/音效播放 */
    public ClientLevel getLevel() {
        return level;
    }

    /** @return 世界空间命中点 */
    public Vec3 getHitPos() {
        return hitPos;
    }

    /** @return 命中类型（ENTITY / BLOCK / PIERCE） */
    public HitType getHitType() {
        return hitType;
    }

    /** @return 被命中实体的网络 ID；非实体命中时为 -1 */
    public int getHitEntityId() {
        return hitEntityId;
    }

    /** @return 枪械 sounds 槽位解析的命中音效 ID；未配置时为 null */
    @Nullable
    public ResourceLocation getSoundId() {
        return soundId;
    }
}
