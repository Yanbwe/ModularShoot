package org.yanbwe.modularshoot.state;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * Ownership domain of a persistent state entry.
 *
 * <p>Each registered state belongs to exactly one domain, which determines
 * where the state is stored and scoped:
 * <ul>
 *   <li>{@link #GUN} — per-gun states, persisted on the gun item's data
 *       components.</li>
 *   <li>{@link #PLAYER} — per-player states, persisted on the player's
 *       save data.</li>
 *   <li>{@link #BULLET} — per-bullet states, persisted on the bullet
 *       entity for its lifetime.</li>
 * </ul>
 *
 * <p>Serialized as the lowercase name ({@code "gun"}, {@code "player"},
 * {@code "bullet"}) in datapack JSON.</p>
 *
 * @see org.yanbwe.modularshoot.state.StateDefinition#domain
 */
public enum StateDomain implements StringRepresentable {
    GUN("gun"),
    PLAYER("player"),
    BULLET("bullet");

    /** Codec that serializes the enum via its lowercase {@link #getSerializedName}. */
    public static final Codec<StateDomain> CODEC = StringRepresentable.fromEnum(StateDomain::values);

    private final String serializedName;

    StateDomain(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}
