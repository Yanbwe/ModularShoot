package org.yanbwe.modularshoot.registry.variant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

/**
 * A random variant definition for the {@code modularshoot:variants} datapack
 * table (机制四 §6.1, 第 7 张 DataPackRegistry). Each variant bundles the
 * mutually-exclusive single-value fields ({@code damage_type},
 * {@code bullet_style_override}) with mergeable ones ({@code traits},
 * {@code stats}) plus a base weight, so a single per-shot roll can rewrite a
 * {@link org.yanbwe.modularshoot.bullet.BulletSnapshot} coherently.
 *
 * <p>The registry key of a variant is <strong>not</strong> part of this
 * record; it is provided by the enclosing registry (the datapack JSON file
 * path {@code data/<namespace>/modularshoot/variants/<id>.json} determines
 * the id).</p>
 *
 * <h2>JSON shape (spec §6.1)</h2>
 *
 * <pre>{@code
 * {
 *   "base_weight": 3,
 *   "traits": { "examplemod:fire": true },
 *   "stats": { "modularshoot:hit_damage": 15.0 },
 *   "damage_type": "minecraft:in_fire",
 *   "bullet_style_override": { "base": {...}, "modifiers": [...] }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code base_weight} — optional, defaults to {@code 0.0}. This is the
 *       <strong>authoritative</strong> weight (not a hint), used only when
 *       <em>no</em> gun/plugin declares this variant in its pool (典型场景：
 *       仅由 {@code registerVariantContributor} 引入的变体); declared
 *       gun/plugin weights take precedence and are used verbatim
 *       (设计决策 1，规格 §6.1/§6.2 开放点).</li>
 *   <li>{@code traits} — optional, defaults to the empty map. Boolean traits
 *       merged into the snapshot at apply time; unlisted traits are kept.</li>
 *   <li>{@code stats} — optional, defaults to the empty map. Attribute values
 *       overriding only the declared keys at apply time
 *       (设计决策 2：只覆盖声明键，不整体替换).</li>
 *   <li>{@code damage_type} — optional. When present it overrides the
 *       {@code ammo_damage_type} preset (spec §6.4, 变体优先).</li>
 *   <li>{@code bullet_style_override} — optional
 *       {@link BulletStyle}. Staged on the snapshot and consumed by
 *       {@code VisualCompositionService} at compose time as the highest
 *       priority base source; never serialised to clients (spec V6).</li>
 * </ul>
 *
 * <p><strong>逐弹丸语义</strong>: the variant pool is rolled <em>once per
 * pellet</em>, so pellets of one shot may end up with different variants
 * (spec §6.4); per-pellet differentiation is handled by the roll itself,
 * while shoot-effect contributors (机制三) further rewrite individual
 * pellets after the roll.</p>
 *
 * @param baseWeight          authoritative base weight (not a hint) used when
 *                            nothing else declares this
 *                            variant in the per-shot pool; default {@code 0.0}
 * @param traits              boolean traits merged into the snapshot on apply;
 *                            empty when absent
 * @param stats               stat values overriding only the declared keys on
 *                            apply; empty when absent
 * @param damageType          damage type overriding the ammo preset when
 *                            present; empty when absent
 * @param bulletStyleOverride visual style override staged for composition
 *                            when present; empty when absent
 */
public record VariantDefinition(
        double baseWeight,
        Map<ResourceLocation, Boolean> traits,
        Map<ResourceLocation, Double> stats,
        Optional<ResourceLocation> damageType,
        Optional<BulletStyle> bulletStyleOverride
) {
    public static final Codec<VariantDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.DOUBLE.optionalFieldOf("base_weight", 0.0).forGetter(VariantDefinition::baseWeight),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.BOOL)
                            .optionalFieldOf("traits", Map.of()).forGetter(VariantDefinition::traits),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE)
                            .optionalFieldOf("stats", Map.of()).forGetter(VariantDefinition::stats),
                    ResourceLocation.CODEC.optionalFieldOf("damage_type").forGetter(VariantDefinition::damageType),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style_override")
                            .forGetter(VariantDefinition::bulletStyleOverride)
            ).apply(instance, VariantDefinition::new)
    );
}
