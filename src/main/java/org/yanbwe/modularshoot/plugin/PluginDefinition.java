package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.registry.gun.BulletStyle;

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
 *       inherited from the category.</li>
 *   <li>{@code item_icon} &mdash; required texture path shown in the
 *       inventory.</li>
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
 *   <li>{@code name} &mdash; optional display name; supports colour codes
 *       ({@code §}).</li>
 *   <li>{@code brief} &mdash; optional one-line summary.</li>
 *   <li>{@code description} &mdash; optional long-form description.</li>
 *   <li>{@code color} &mdash; optional name colour (e.g.
 *       {@code "#FF4444"}).</li>
 * </ul>
 *
 * @param tags           tag ids for category intersection matching
 * @param priority       conflict priority; not inherited from the category
 * @param itemIcon       required inventory icon texture path
 * @param modifiers      attribute modifiers applied on installation
 * @param traits         boolean trait overrides keyed by trait id
 * @param exclusiveGroup optional mutual-exclusion group id; empty when
 *                       unrestricted
 * @param bulletStyle    optional projectile appearance override; empty when
 *                       the gun's own style is kept
 * @param textureOverlay optional texture overlay; empty when none is stacked
 * @param name           optional display name; supports colour codes
 * @param brief          optional one-line summary
 * @param description    optional long-form description
 * @param color          optional name colour
 */
public record PluginDefinition(
        List<ResourceLocation> tags,
        int priority,
        ResourceLocation itemIcon,
        List<PluginModifier> modifiers,
        Map<ResourceLocation, Boolean> traits,
        Optional<String> exclusiveGroup,
        Optional<BulletStyle> bulletStyle,
        Optional<TextureOverlay> textureOverlay,
        Optional<String> name,
        Optional<String> brief,
        Optional<String> description,
        Optional<String> color
) {
    public static final Codec<PluginDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(PluginDefinition::tags),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(PluginDefinition::priority),
                    ResourceLocation.CODEC.fieldOf("item_icon").forGetter(PluginDefinition::itemIcon),
                    PluginModifier.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(PluginDefinition::modifiers),
                    Codec.unboundedMap(ResourceLocation.CODEC, Codec.BOOL).optionalFieldOf("traits", Map.of()).forGetter(PluginDefinition::traits),
                    Codec.STRING.optionalFieldOf("exclusive_group").forGetter(PluginDefinition::exclusiveGroup),
                    BulletStyle.CODEC.optionalFieldOf("bullet_style").forGetter(PluginDefinition::bulletStyle),
                    TextureOverlay.CODEC.optionalFieldOf("texture_overlay").forGetter(PluginDefinition::textureOverlay),
                    Codec.STRING.optionalFieldOf("name").forGetter(PluginDefinition::name),
                    Codec.STRING.optionalFieldOf("brief").forGetter(PluginDefinition::brief),
                    Codec.STRING.optionalFieldOf("description").forGetter(PluginDefinition::description),
                    Codec.STRING.optionalFieldOf("color").forGetter(PluginDefinition::color)
            ).apply(instance, PluginDefinition::new)
    );
}
