package org.yanbwe.modularshoot.registry;

import com.mojang.serialization.Codec;

/**
 * Minimal stub for a {@code modularshoot:plugin_types} datapack registry entry.
 *
 * <p>This is a placeholder for the M1 milestone so the dynamic registry can be
 * registered and synced. The full plugin type definition — tags, priority, and
 * display info — will be expanded in M2.</p>
 *
 * <p>The registry key (the plugin type id) is supplied by the registry itself
 * and is therefore not a field of this record.</p>
 *
 * @see ModularShootRegistries#PLUGIN_TYPES_KEY
 */
public record PluginType() {
    /** Codec that decodes an empty {@link PluginType}; to be expanded in M2. */
    public static final Codec<PluginType> CODEC = Codec.unit(PluginType::new);
}
