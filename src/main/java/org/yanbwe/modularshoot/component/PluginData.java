package org.yanbwe.modularshoot.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable data component stored on a {@code modularshoot:plugin} item stack.
 *
 * <p>All plugin items share the single {@code modularshoot:plugin} item id, so
 * the item id alone cannot tell which plugin a stack represents. This component
 * carries the {@code pluginId} that points into the {@code modularshoot:plugins}
 * registry, letting the framework resolve the full plugin definition (traits,
 * category, slot layout, etc.) from the stack alone. The component is persisted
 * with the item NBT so it survives drops, chests, relogs and dimension changes.</p>
 *
 * @param pluginId the plugin definition id in the {@code modularshoot:plugins} registry
 */
public record PluginData(
        ResourceLocation pluginId
) {
    public static final Codec<PluginData> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("plugin_id").forGetter(PluginData::pluginId)
            ).apply(instance, PluginData::new)
    );
}
