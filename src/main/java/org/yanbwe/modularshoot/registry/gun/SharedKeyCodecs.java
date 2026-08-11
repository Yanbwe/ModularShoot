package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Shared map-key codec: bare keys (no colon) default to the
 * {@code modularshoot} namespace.
 *
 * <p>Extracted as a util so both {@link GunDefinition} (slots/traits/stats/
 * variants maps) and {@link org.yanbwe.modularshoot.plugin.PluginDefinition}
 * ({@code adds_slots}) can cite the one definition, avoiding drift between
 * near-identical inline copies.</p>
 */
public final class SharedKeyCodecs {

    private SharedKeyCodecs() {
    }

    /**
     * Map 键 codec：裸键（无冒号）默认补 {@code modularshoot} 命名空间。
     *
     * <p>与插件修饰符 attribute 裸键规则一致（见
     * {@link org.yanbwe.modularshoot.attribute.AttributeModifierService#resolveAttributeHolder}）：
     * 逻辑属性/特性/插槽/变体 id 均属模组命名空间，裸键落 {@code minecraft} 是历史错误。
     * 实现限制：裸键解析后命名空间为 {@code minecraft}，本 codec 无法区分"裸键"与
     * 作者显式书写的 {@code minecraft:} 前缀键，两者都会被改写为
     * {@code modularshoot:}——数据包作者如需其他命名空间（如 {@code mypack:combat}）
     * 必须显式书写，不得依赖 {@code minecraft:} 前缀。
     * encode 侧保持原样（round-trip 稳定：改写后的 {@code modularshoot:} 键再 encode
     * 不变）。</p>
     */
    public static final Codec<ResourceLocation> MODULARSHOOT_KEY = ResourceLocation.CODEC.xmap(
            loc -> loc.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)
                    ? ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, loc.getPath())
                    : loc,
            loc -> loc);
}
