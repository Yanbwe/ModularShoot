package org.yanbwe.modularshoot.attribute;

import java.util.Optional;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the {@link PlayerAttributeValueReader} for a player-side gun stack.
 *
 * <p>This is the extension point for declaring sides that own the player mount
 * of a gun: they inspect the stack (by identity, by {@code gun_data}, by item,
 * etc.) and return a reader that can compute final attribute values. Providers
 * are consulted in registration order and the first provider returning a
 * non-empty {@link Optional} wins; returning {@link Optional#empty()} lets
 * later providers try.</p>
 *
 * <p>No real {@link Player} entity is required: the viewer is intentionally
 * {@link Nullable} so callers such as tooltips can resolve values from synced
 * data or an available viewer without requiring the player to exist.</p>
 *
 * @see PlayerAttributeSourceRegistry
 */
@FunctionalInterface
public interface PlayerAttributeSourceProvider {

    /**
     * Tries to resolve a value reader for the given stack.
     *
     * @param stack  the item stack to inspect; must not be {@code null}
     * @param viewer the player viewing the stack, or {@code null} when no
     *               player entity is available
     * @return the resolved reader, or {@link Optional#empty()} to fall through
     *         to the next provider
     */
    Optional<PlayerAttributeValueReader> resolve(ItemStack stack, @Nullable Player viewer);
}