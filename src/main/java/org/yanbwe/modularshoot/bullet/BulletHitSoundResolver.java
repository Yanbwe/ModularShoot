package org.yanbwe.modularshoot.bullet;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.network.BulletHitS2CPacket.HitType;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.gun.GunSounds;

/**
 * 子弹命中音效解析器（设计文档 §音效系统 — 数据驱动命中音效）。
 *
 * <p>命中音效由枪械定义的 {@code sounds} 槽位数据驱动：实体命中读
 * {@code hit_entity}、方块命中读 {@code hit_block}、穿透命中读
 * {@code hit_pierce}。槽位未配置时返回 {@link Optional#empty()}，调用方
 * 静音处理——框架从不硬编码音效。</p>
 *
 * <p>这是无状态工具类；纯函数 {@link #resolve(GunDefinition, HitType)}
 * 供单元测试直接验证，装配函数 {@link #resolve(BulletRecord, Level, HitType)}
 * 由广播服务在服务端调用。</p>
 */
public final class BulletHitSoundResolver {

    /** 实体命中音效槽位名。 */
    private static final String HIT_ENTITY_SLOT = "hit_entity";
    /** 方块命中音效槽位名。 */
    private static final String HIT_BLOCK_SLOT = "hit_block";
    /** 穿透命中音效槽位名。 */
    private static final String HIT_PIERCE_SLOT = "hit_pierce";

    private BulletHitSoundResolver() {
    }

    /**
     * 将命中类型映射到枪械定义中的音效槽位并读取（纯函数，可单测）。
     *
     * @param definition 枪械定义（不可为 null）
     * @param hitType    命中类型（ENTITY / BLOCK / PIERCE）
     * @return 该命中类型配置的音效 ID；槽位未配置时返回 {@link Optional#empty()}
     */
    public static Optional<ResourceLocation> resolve(GunDefinition definition, HitType hitType) {
        String slot = switch (hitType) {
            case ENTITY -> HIT_ENTITY_SLOT;
            case BLOCK -> HIT_BLOCK_SLOT;
            case PIERCE -> HIT_PIERCE_SLOT;
        };
        return GunSounds.get(definition, slot);
    }

    /**
     * 从子弹记录回溯发射枪械定义并解析命中音效（服务端装配函数）。
     *
     * <p>通过 {@code snapshot.getGunId()} 查动态注册表；枪械定义不存在
     * （注册表尚未加载 / 定义被移除）时返回 {@link Optional#empty()}，调用
     * 方静音处理，不抛异常。</p>
     *
     * @param bullet  子弹记录（不可为 null）
     * @param level   子弹所在世界（用于查动态注册表）
     * @param hitType 命中类型
     * @return 该次命中应播放的音效 ID；无法解析时返回 {@link Optional#empty()}
     */
    public static Optional<ResourceLocation> resolve(BulletRecord bullet, Level level, HitType hitType) {
        return GunRegistry.getGun(level, bullet.getSnapshot().getGunId())
                .flatMap(definition -> resolve(definition, hitType));
    }
}
