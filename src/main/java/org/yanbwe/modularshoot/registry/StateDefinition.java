package org.yanbwe.modularshoot.registry;

import com.mojang.serialization.Codec;

/**
 * Minimal stub for a {@code modularshoot:states} datapack registry entry.
 *
 * <p>This is a placeholder for the M1 milestone so the dynamic registry can be
 * registered and synced. The full state definition — ownership domain, value
 * type, default value, and display info — will be expanded in M5.</p>
 *
 * <p>The registry key (the state id) is supplied by the registry itself and is
 * therefore not a field of this record.</p>
 *
 * @see ModularShootRegistries#STATES_KEY
 */
public record StateDefinition() {
    /** Codec that decodes an empty {@link StateDefinition}; to be expanded in M5. */
    public static final Codec<StateDefinition> CODEC = Codec.unit(StateDefinition::new);
}
