package org.yanbwe.modularshoot.plugin;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;
import org.yanbwe.modularshoot.registry.gun.TextureScaleMode;

/**
 * Immutable definition of a plugin entry in the {@code modularshoot:plugins}
 * registry.
 *
 * <p>The registry key (the plugin id, e.g. {@code modularshoot:rapid_barrel})
 * is supplied by the registry itself and is therefore <strong>not</strong> a
 * field of this record. Callers obtain it from the registry holder/key.</p>
 *
 * <p>A plugin is attached to a gun when the gun's plugin slot category shares
 * at least one tag with the plugin (intersection match). When multiple plugins
 * in the same slot emit conflicting traits, the {@code priority} field breaks
 * the tie &mdash; a plugin does <strong>not</strong> inherit the priority of
 * its category; each plugin carries its own value.</p>
 *
 * <p>When {@code tags} is empty the plugin cannot match any category and
 * therefore cannot be installed on any gun; the registry loader emits a
 * {@code WARN} for such entries but the codec itself does not raise an
 * error.</p>
 *
 * <p>JSON keys (see 设计文档 §插件数据包 JSON):
 * <ul>
 *   <li>{@code tags} &mdash; tag ids used for category intersection matching;
 *       defaults to an empty list.</li>
 *   <li>{@code priority} &mdash; conflict priority; defaults to {@code 0}. Not
 *       inherited from the category. Also the fallback for visual base
 *       election when {@code visual_priority} is absent.</li>
 *   <li>{@code visual_priority} &mdash; optional visual base-election
 *       priority; defaults to the {@code priority} value. Only consulted when
 *       electing the visual base among plugins' bullet styles &mdash; it never
 *       affects trait-conflict resolution.</li>
 *   <li>{@code item_icon} &mdash; required texture path shown in the
 *       inventory.</li>
 *   <li>{@code texture_scale} &mdash; optional, defaults to {@code auto}.
 *       Controls whether the {@code item_icon} geometry scales with the
 *       texture resolution ({@code auto}) or stays fixed in the 16×16 unit
 *       grid ({@code fixed}).</li>
 *   <li>{@code modifiers} &mdash; attribute modifiers; defaults to an empty
 *       list.</li>
 *   <li>{@code traits} &mdash; boolean trait overrides keyed by trait id;
 *       defaults to an empty map.</li>
 *   <li>{@code exclusive_group} &mdash; optional mutual-exclusion group id.
 *       Plugins sharing the same group cannot be installed together; absent
 *       means no restriction.</li>
 *   <li>{@code bullet_style} &mdash; optional projectile appearance override,
 *       same shape as a gun definition's {@code bullet_style}.</li>
 *   <li>{@code texture_overlay} &mdash; optional texture stacked over the
 *       gun's base texture.</li>
 *   <li>{@code gun_outline} &mdash; optional whole-gun outline: a stroke
 *       painted around the silhouette of the final composited gun texture.
 *       Multiple plugins' outlines stack concentrically, wider strokes
 *       outside thinner ones.</li>
 *   <li>{@code extra_values} &mdash; optional namespaced numeric extension
 *       fields (e.g. {@code {"raritycore:rarity": 5.0}}), defaulting to an
 *       empty map. The framework only carries and aggregates these values
 *       (see {@link PluginExtraValueService}) and never interprets their
 *       meaning &mdash; integration mods read them via
 *       {@code ModularShootAPI.getExtraValueSums} and translate them into
 *       their own systems (e.g. a rarity component).</li>
 *   <li>{@code name} &mdash; optional display name; supports colour codes
 *       ({@code §}).</li>
 *   <li>{@code brief} &mdash; optional one-line summary.</li>
 *   <li>{@code description} &mdash; optional long-form description.</li>
 *   <li>{@code color} &mdash; optional name colour (e.g.
 *       {@code "#FF4444"}).</li>
 *   <li>{@code adds_variants} &mdash; optional "variant id → base weight"
 *       map appended to the gun's per-shot variant pool (设计规格 §6.2 来源表：
 *       插件向枪的池子追加变体 id + base_weight); defaults to an empty
 *       map.</li>
 * </ul>
 *
 * @param tags           tag ids for category intersection matching
 * @param priority       conflict priority; not inherited from the category;
 *                       also the visual base-election fallback when
 *                       {@code visual_priority} is absent
 * @param itemIcon       required inventory icon texture path
 * @param textureScale   item-icon geometry scaling with texture resolution;
 *                       defaults to {@code auto}
 * @param modifiers      attribute modifiers applied on installation
 * @param traits         boolean trait overrides keyed by trait id
 * @param exclusiveGroup optional mutual-exclusion group id; empty when
 *                       unrestricted
 * @param bulletStyle    optional projectile appearance override; empty when
 *                       the gun's own style is kept
 * @param textureOverlay optional texture overlay; empty when none is stacked
 * @param gunOutline     optional whole-gun outline stroked around the final
 *                       composited texture; empty when none is drawn
 * @param extraValues    namespaced numeric extension fields; empty when none
 *                       are declared
 * @param name           optional display name; supports colour codes
 * @param brief          optional one-line summary
 * @param description    optional long-form description
 * @param color          optional name colour
 * @param addsVariants   optional variant id → base weight map appended to the
 *                       gun's per-shot variant pool (设计规格 §6.2); empty
 *                       when the plugin adds no variants
 * @param visualPriority visual base-election priority, only used when electing
 *                       the visual base among plugins' bullet styles; empty
 *                       falls back to {@code priority} (see
 *                       {@link #visualPriorityOrFallback()})
 */
public record PluginDefinition(
        List<ResourceLocation> tags,
        int priority,
        ResourceLocation itemIcon,
        TextureScaleMode textureScale,
        List<PluginModifier> modifiers,
        Map<ResourceLocation, Boolean> traits,
        Optional<String> exclusiveGroup,
        Optional<BulletStyle> bulletStyle,
        Optional<TextureOverlay> textureOverlay,
        Optional<OutlineSpec> gunOutline,
        Map<ResourceLocation, Double> extraValues,
        Optional<String> name,
        Optional<String> brief,
        Optional<String> description,
        Optional<String> color,
        Map<ResourceLocation, Double> addsVariants,
        Optional<Integer> visualPriority
) {
    public static final Codec<PluginDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(PluginDefinition::tags),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(PluginDefinition::priority),
                    ResourceLocation.CODEC.fieldOf("item_icon").forGetter(PluginDefinition::itemIcon),
                    TextureScaleMode.CODEC.optionalFieldOf("texture_scale", TextureScaleMode.AUTO).forGetter(PluginDefinition::textureScale),
                    PluginModifier.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(PluginDefinition::modifiers),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.BOOL).optionalFieldOf("traits", Map.of()).forGetter(PluginDefinition::traits),
                    Codec.STRING.optionalFieldOf("exclusive_group").forGetter(PluginDefinition::exclusiveGroup),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style").forGetter(PluginDefinition::bulletStyle),
                    TextureOverlay.CODEC.optionalFieldOf("texture_overlay").forGetter(PluginDefinition::textureOverlay),
                    OutlineSpec.CODEC.optionalFieldOf("gun_outline").forGetter(PluginDefinition::gunOutline),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE)
                            .optionalFieldOf("extra_values", Map.of())
                            .forGetter(PluginDefinition::extraValues),
                    Codec.STRING.optionalFieldOf("name").forGetter(PluginDefinition::name),
                    Codec.STRING.optionalFieldOf("brief").forGetter(PluginDefinition::brief),
                    Codec.STRING.optionalFieldOf("description").forGetter(PluginDefinition::description),
                    Codec.STRING.optionalFieldOf("color").forGetter(PluginDefinition::color),
                    // Kind1.group 的参数上限是 16（DFU 8.0.16），record 有 17 个
                    // 组件：把 adds_variants 与 visual_priority 嵌套为一组 Pair 合并
                    // 成第 16 个 group 参数，两个键仍写入同一张扁平 JSON map。
                    instance.group(
                            Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE)
                                    .optionalFieldOf("adds_variants", Map.of())
                                    .forGetter(PluginDefinition::addsVariants),
                            Codec.INT.optionalFieldOf("visual_priority")
                                    .forGetter(PluginDefinition::visualPriority)
                    ).apply(instance, Pair::new)
            ).apply(instance, (tags, priority, itemIcon, textureScale, modifiers, traits, exclusiveGroup,
                    bulletStyle, textureOverlay, gunOutline, extraValues, name, brief, description, color,
                    lastPair) -> new PluginDefinition(tags, priority, itemIcon, textureScale, modifiers, traits,
                    exclusiveGroup, bulletStyle, textureOverlay, gunOutline, extraValues, name, brief, description,
                    color, lastPair.getFirst(), lastPair.getSecond()))
    );

    /**
     * 视觉选举优先级：显式声明时用 visual_priority，否则回退 priority。
     *
     * @return the priority used when this plugin's bullet style base competes
     *         in visual base election
     */
    public int visualPriorityOrFallback() {
        return visualPriority.orElse(priority);
    }
}
