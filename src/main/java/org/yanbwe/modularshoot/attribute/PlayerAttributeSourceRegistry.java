package org.yanbwe.modularshoot.attribute;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Process-wide registry of {@link PlayerAttributeSourceProvider}s for
 * player-side attribute resolution.
 *
 * <p>Providers are kept in registration order. {@link #resolve} iterates them
 * in that order and returns the first non-empty result; therefore the earliest
 * registered provider that recognizes a stack is authoritative. Registering a
 * provider is global, survives {@code /reload}, and affects every player-side
 * gun tooltip query.</p>
 *
 * <p>There is intentionally <b>no unregister operation</b>: registration is
 * static process state, following the project's existing registry pattern.
 * Tests isolate their registrations by using unique {@link ItemStack} instance
 * identity (reference equality) so static providers cannot pollute other
 * tests.</p>
 */
public final class PlayerAttributeSourceRegistry {

    private static final java.util.List<PlayerAttributeSourceProvider> PROVIDERS =
            new CopyOnWriteArrayList<>();

    private PlayerAttributeSourceRegistry() {
    }

    /**
     * Registers a player attribute source provider.
     *
     * <p>The provider is appended after all previously registered providers.
     * Safe to call during mod common-setup; the registration is process-wide
     * and remains active until the JVM exits.</p>
     *
     * @param provider the provider to register; must not be {@code null}
     * @throws NullPointerException if {@code provider} is {@code null}
     */
    public static void register(PlayerAttributeSourceProvider provider) {
        if (provider == null) {
            throw new NullPointerException("provider");
        }
        PROVIDERS.add(provider);
    }

    /**
     * Resolves a {@link PlayerAttributeValueReader} for the given stack.
     *
     * <p>Consults providers in registration order and returns the first
     * non-empty result. Returns {@link Optional#empty()} when no provider
     * recognises the stack.</p>
     *
     * @param stack  the item stack to resolve; must not be {@code null}
     * @param viewer the player viewing the stack, or {@code null} when no
     *               player entity is available
     * @return the first resolved reader, or empty when all providers fall
     *         through
     */
    public static Optional<PlayerAttributeValueReader> resolve(
            ItemStack stack, @Nullable Player viewer) {
        for (PlayerAttributeSourceProvider provider : PROVIDERS) {
            Optional<PlayerAttributeValueReader> result = provider.resolve(stack, viewer);
            if (result.isPresent()) {
                return result;
            }
        }
        return Optional.empty();
    }
}