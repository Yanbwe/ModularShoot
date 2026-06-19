package org.yanbwe.modularshoot.registry;

import com.mojang.serialization.Codec;

/**
 * Minimal stub for a {@code modularshoot:plugins} datapack registry entry.
 *
 * <p>This is a placeholder for the M1 milestone so the dynamic registry can be
 * registered and synced. The full plugin definition — tags, attribute
 * modifiers, traits, mutual-exclusion groups, and display info — will be
 * expanded in M2.</p>
 *
 * <p>The registry key (the plugin id) is supplied by the registry itself and is
 * therefore not a field of this record.</p>
 *
 * @see ModularShootRegistries#PLUGINS_KEY
 */
public record PluginDefinition() {
    /** Codec that decodes an empty {@link PluginDefinition}; to be expanded in M2. */
    public static final Codec<PluginDefinition> CODEC = Codec.unit(PluginDefinition::new);
}
