package org.yanbwe.modularshoot.plugin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable definition of a plugin category entry in the
 * {@code modularshoot:plugin_types} registry.
 *
 * <p>A plugin category (plugin type) describes a slot kind that plugins can be
 * installed into. Categories are registered dynamically by other mods through
 * the registration API and are loaded from the
 * {@code modularshoot:plugin_types} datapack registry.</p>
 *
 * <p>The registry key (the category id, e.g. {@code modularshoot:barrel}) is
 * supplied by the registry itself and is therefore <strong>not</strong> a
 * field of this record. Callers obtain it from the registry holder/key.</p>
 *
 * <p>JSON keys (see 设计文档 §插件种类数据包 JSON):
 * <ul>
 *   <li>{@code tags} — tag list used to match plugins. A category matches a
 *       plugin when their tag sets intersect. Defaults to an empty list; an
 *       empty tag set means the category cannot match any plugin. The Codec
 *       accepts an empty list without error — a {@code WARN} is logged at
 *       registration time instead, which is outside the Codec's
 *       responsibility.</li>
 *   <li>{@code priority} — display priority, higher values sort first.
 *       Optional, defaults to {@code 0}. Also serves as the secondary sort
 *       key for the install auto-selection algorithm. <strong>Note:</strong>
 *       the category priority does <em>not</em> affect plugin trait conflict
 *       priority.</li>
 *   <li>{@code name} — optional category display name.</li>
 *   <li>{@code color} — optional colour for the category display name (e.g.
 *       {@code "#FFAA00"}).</li>
 * </ul>
 *
 * @param tags     tag list used to match plugins via set intersection; empty
 *                 when no plugin can match (logged at registration, not by
 *                 the Codec)
 * @param priority display priority and auto-selection secondary sort key;
 *                 defaults to {@code 0}; does not affect trait conflict
 *                 priority
 * @param name     optional category display name; empty when unspecified
 * @param color    optional category display name colour; empty when
 *                 unspecified
 */
public record PluginTypeDefinition(
        List<ResourceLocation> tags,
        int priority,
        Optional<String> name,
        Optional<String> color
) {
    public static final Codec<PluginTypeDefinition> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.listOf().optionalFieldOf("tags", List.of()).forGetter(PluginTypeDefinition::tags),
                    Codec.INT.optionalFieldOf("priority", 0).forGetter(PluginTypeDefinition::priority),
                    Codec.STRING.optionalFieldOf("name").forGetter(PluginTypeDefinition::name),
                    Codec.STRING.optionalFieldOf("color").forGetter(PluginTypeDefinition::color)
            ).apply(instance, PluginTypeDefinition::new)
    );
}
