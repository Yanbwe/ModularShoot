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
     * encode 侧保持原样（round-trip 稳定：完整命名空间键 decode 不动）。</p>
     */
    public static final Codec<ResourceLocation> MODULARSHOOT_KEY = ResourceLocation.CODEC.xmap(
            loc -> loc.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)
                    ? ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, loc.getPath())
                    : loc,
            loc -> loc);
}
