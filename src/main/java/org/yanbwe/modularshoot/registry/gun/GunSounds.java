package org.yanbwe.modularshoot.registry.gun;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * 枪械定义音效槽位读取工具（设计文档 §音效系统 — 数据驱动音效）。
 *
 * <p>枪械定义的 {@code sounds} 字段是"槽位名 → 音效 ID"映射（如
 * {@code "shoot": "minecraft:entity.firework_rocket.launch"}）。本工具提供
 * 统一的槽位读取入口：槽位缺失或未配置时返回 {@link Optional#empty()}，
 * 由调用方决定静音，框架从不硬编码音效。另有 {@link #getRange(GunDefinition)}
 * 读取枪械定义的 {@code sound_range}（可闻半径覆盖），缺省时由音效事件
 * 自带 range（默认 16 格）决定。</p>
 *
 * <p>这是一个无状态工具类，所有方法均为静态，不可实例化。</p>
 */
public final class GunSounds {

    private GunSounds() {
    }

    /**
     * 读取枪械定义中指定槽位的音效 ID。
     *
     * @param definition 枪械定义（不可为 null）
     * @param slot       音效槽位名（如 {@code "hit_block"}、{@code "plugin_install"}）
     * @return 该槽位的音效 ID；槽位未配置时返回 {@link Optional#empty()}
     */
    public static Optional<ResourceLocation> get(GunDefinition definition, String slot) {
        return Optional.ofNullable(definition.sounds().get(slot));
    }

    /**
     * 读取枪械定义的 sound_range（可闻半径覆盖），空表示用 SoundEvent 自带 range。
     *
     * @param definition 枪械定义（不可为 null）
     * @return 枪械定义的 sound_range；未声明时返回 {@link Optional#empty()}
     */
    public static Optional<Float> getRange(GunDefinition definition) {
        return definition.soundRange();
    }
}
