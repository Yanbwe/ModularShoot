package org.yanbwe.modularshoot.registry.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable binding of a vanilla item id to a plugin entry id (设计规格
 * 物品绑定系统 §3.1).
 *
 * <p>Like {@link GunItemBinding}, the binding is <strong>item-id level</strong>:
 * it applies to every instance of the bound item, and the entry key under which
 * the binding is declared may be any file id. The bound plugin entry is
 * identified by its registry key.</p>
 *
 * <p>JSON format:</p>
 * <pre>{@code
 * {
 *   "item":   "minecraft:stick",
 *   "plugin": "mypack:light_plugin"
 * }
 * }</pre>
 *
 * <p>Both fields are required; decoding an object missing either one fails.</p>
 *
 * @param itemId   the bound item's registry id (e.g. {@code minecraft:stick})
 * @param pluginId the target plugin entry's registry key (e.g. {@code mypack:light_plugin})
 */
public record PluginItemBinding(ResourceLocation itemId, ResourceLocation pluginId) {

    public static final Codec<PluginItemBinding> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("item").forGetter(PluginItemBinding::itemId),
                    ResourceLocation.CODEC.fieldOf("plugin").forGetter(PluginItemBinding::pluginId)
            ).apply(instance, PluginItemBinding::new)
    );
}
