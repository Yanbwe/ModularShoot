package org.yanbwe.modularshoot.registry;

import com.mojang.serialization.Codec;

/**
 * Minimal stub for a {@code modularshoot:traits} datapack registry entry.
 *
 * <p>This is a placeholder for the M1 milestone so the dynamic registry can be
 * registered and synced. The full trait definition — default value, runtime
 * hook binding, and display info — will be expanded in M5.</p>
 *
 * <p>The registry key (the trait id) is supplied by the registry itself and is
 * therefore not a field of this record.</p>
 *
 * @see ModularShootRegistries#TRAITS_KEY
 */
public record Trait() {
    /** Codec that decodes an empty {@link Trait}; to be expanded in M5. */
    public static final Codec<Trait> CODEC = Codec.unit(Trait::new);
}
