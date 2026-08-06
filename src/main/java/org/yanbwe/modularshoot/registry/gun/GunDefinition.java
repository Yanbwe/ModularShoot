package org.yanbwe.modularshoot.registry.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

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
 *   <li>{@code stats} — attribute id → value. Keys <strong>must</strong> be
 *       fully namespaced (e.g. {@code "modularshoot:hit_damage"}); bare names
 *       resolve to the {@code minecraft} namespace.</li>
 *   <li>{@code traits} — trait id → flag. Keys must be fully namespaced.</li>
 *   <li>{@code slots} — plugin category id → slot count. Keys must be fully
 *       namespaced.</li>
 *   <li>{@code sounds} — sound slot name (e.g. {@code "shoot"}) → sound event
 *       id.</li>
 *   <li>{@code bullet_style} — optional projectile appearance.</li>
 *   <li>{@code variants} — variant id → base weight, feeding the per-shot
 *       variant pool (设计规格 §6.2 来源表：枪械声明变体 id + base_weight);
 *       optional, defaults to an empty map.</li>
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
    public static final Codec<GunDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.STRING.optionalFieldOf("name").forGetter(GunDefinition::name),
                    ResourceLocation.CODEC.fieldOf("texture").forGetter(GunDefinition::texture),
                    ResourceLocation.CODEC.optionalFieldOf("shoot_texture").forGetter(GunDefinition::shootTexture),
                    ShootTextureMode.CODEC.optionalFieldOf("shoot_texture_mode", ShootTextureMode.PER_SHOT).forGetter(GunDefinition::shootTextureMode),
                    TextureScaleMode.CODEC.optionalFieldOf("texture_scale", TextureScaleMode.AUTO).forGetter(GunDefinition::textureScale),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE).optionalFieldOf("stats", Map.of()).forGetter(GunDefinition::stats),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.BOOL).optionalFieldOf("traits", Map.of()).forGetter(GunDefinition::traits),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.INT).optionalFieldOf("slots", Map.of()).forGetter(GunDefinition::slots),
                    Codec.unboundedMap(Codec.STRING, ResourceLocation.CODEC).optionalFieldOf("sounds", Map.of()).forGetter(GunDefinition::sounds),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style").forGetter(GunDefinition::bulletStyle),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE).optionalFieldOf("variants", Map.of()).forGetter(GunDefinition::variants)
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
