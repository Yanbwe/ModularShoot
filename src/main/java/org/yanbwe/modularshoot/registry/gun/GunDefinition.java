package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.ModularShoot;

/**
 * Immutable definition of a gun entry in the {@code modularshoot:guns}
 * registry.
 *
 * <p>The registry key (the gun id, e.g. {@code modularshoot:sniper_rifle}) is
 * supplied by the registry itself and is therefore <strong>not</strong> a
 * field of this record. Callers obtain it from the registry holder/key.</p>
 *
 * <p>JSON keys (see 设计文档 §枪械数据包 JSON):
 * <ul>
 *   <li>{@code name} — optional display name. Supports colour codes ({@code §})
 *       and the {@code lang:} translation-key prefix. Absent → the caller
 *       falls back to the gun id path.</li>
 *   <li>{@code texture} — required base texture path.</li>
 *   <li>{@code shoot_texture} — optional texture swapped in while firing.
 *       Absent → the base texture is used throughout.</li>
 *   <li>{@code shoot_texture_mode} — optional, defaults to {@code per_shot}.
 *       Only effective when {@code shoot_texture} is present.</li>
 *   <li>{@code texture_scale} — optional, defaults to {@code auto}. Controls
 *       whether the rendered geometry scales with the texture resolution
 *       ({@code auto}) or stays fixed in the 16×16 unit grid ({@code fixed}).</li>
 *   <li>{@code stats} — attribute id → value. Bare keys (no colon) resolve
 *       to the {@code modularshoot} namespace (e.g. {@code "hit_damage"} ≡
 *       {@code "modularshoot:hit_damage"}); fully namespaced keys are kept
 *       as-is.</li>
 *   <li>{@code traits} — trait id → flag. Bare keys resolve to the
 *       {@code modularshoot} namespace.</li>
 *   <li>{@code slots} — plugin category id → slot count. Bare keys resolve
 *       to the {@code modularshoot} namespace.</li>
 *   <li>{@code sounds} — sound slot name (e.g. {@code "shoot"}) → sound event
 *       id.</li>
 *   <li>{@code bullet_style} — optional projectile appearance.</li>
 *   <li>{@code variants} — variant id → base weight, feeding the per-shot
 *       variant pool (设计规格 §6.2 来源表：枪械声明变体 id + base_weight);
 *       optional, defaults to an empty map. Bare keys resolve to the
 *       {@code modularshoot} namespace.</li>
 * </ul>
 *
 * @param name             optional display name; empty when the caller should
 *                         fall back to the gun id path
 * @param texture          base texture path (required)
 * @param shootTexture     optional texture used while firing; empty when the
 *                         base texture should be kept
 * @param shootTextureMode texture-swap timing; defaults to
 *                         {@link ShootTextureMode#PER_SHOT}
 * @param textureScale     geometry scaling with texture resolution; defaults
 *                         to {@link TextureScaleMode#AUTO}
 * @param stats            base attribute values keyed by attribute id
 * @param traits           inherent boolean traits keyed by trait id
 * @param slots            plugin slot configuration keyed by category id
 * @param sounds           sound bindings keyed by slot name
 * @param bulletStyle      optional projectile visual style; empty when the
 *                         default (pure collision body) appearance is used
 * @param variants         optional variant id → base weight map for the
 *                         per-shot variant pool (设计规格 §6.2); empty when
 *                         the gun declares no variants
 */
public record GunDefinition(
        Optional<String> name,
        ResourceLocation texture,
        Optional<ResourceLocation> shootTexture,
        ShootTextureMode shootTextureMode,
        TextureScaleMode textureScale,
        Map<ResourceLocation, Double> stats,
        Map<ResourceLocation, Boolean> traits,
        Map<ResourceLocation, Integer> slots,
        Map<String, ResourceLocation> sounds,
        Optional<BulletStyle> bulletStyle,
        Map<ResourceLocation, Double> variants
) {
    /**
     * Map 键 codec：裸键（无冒号）默认补 {@code modularshoot} 命名空间。
     *
     * <p>与插件修饰符 attribute 裸键规则一致（见
     * {@link org.yanbwe.modularshoot.attribute.AttributeModifierService#resolveAttributeHolder}）：
     * 逻辑属性/特性/插槽/变体 id 均属模组命名空间，裸键落 {@code minecraft} 是历史错误。
     * encode 侧保持原样（round-trip 稳定：完整命名空间键 decode 不动）。</p>
     */
    private static final Codec<ResourceLocation> MODULARSHOOT_KEY = ResourceLocation.CODEC.xmap(
            loc -> loc.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)
                    ? ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, loc.getPath())
                    : loc,
            loc -> loc);

    public static final Codec<GunDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.STRING.optionalFieldOf("name").forGetter(GunDefinition::name),
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(GunDefinition::texture),
                    ResourceLocation.CODEC.optionalFieldOf("shoot_texture").forGetter(GunDefinition::shootTexture),
                    ShootTextureMode.CODEC.optionalFieldOf("shoot_texture_mode", ShootTextureMode.PER_SHOT).forGetter(GunDefinition::shootTextureMode),
                    TextureScaleMode.CODEC.optionalFieldOf("texture_scale", TextureScaleMode.AUTO).forGetter(GunDefinition::textureScale),
                    Codec.unboundedMap(MODULARSHOOT_KEY, Codec.DOUBLE).optionalFieldOf("stats", Map.of()).forGetter(GunDefinition::stats),
                    Codec.unboundedMap(MODULARSHOOT_KEY, Codec.BOOL).optionalFieldOf("traits", Map.of()).forGetter(GunDefinition::traits),
                    Codec.unboundedMap(MODULARSHOOT_KEY, Codec.INT).optionalFieldOf("slots", Map.of()).forGetter(GunDefinition::slots),
                    Codec.unboundedMap(Codec.STRING, ResourceLocation.CODEC).optionalFieldOf("sounds", Map.of()).forGetter(GunDefinition::sounds),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style").forGetter(GunDefinition::bulletStyle),
                    Codec.unboundedMap(MODULARSHOOT_KEY, Codec.DOUBLE).optionalFieldOf("variants", Map.of()).forGetter(GunDefinition::variants)
            ).apply(instance, GunDefinition::new)
    );

    /**
     * Returns the explicit display name, or {@code null} when the name is
     * absent or empty so the caller can fall back to the gun id path.
     *
     * @return the display name string, or {@code null} when none is specified
     */
    @Nullable
    public String nameOrNull() {
        return name.filter(s -> !s.isEmpty()).orElse(null);
    }
}
