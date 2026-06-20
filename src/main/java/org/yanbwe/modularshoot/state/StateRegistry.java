package org.yanbwe.modularshoot.state;

import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Read-only query API for the {@code modularshoot:states} dynamic registry.
 *
 * <p>The state registry is a datapack-driven dynamic registry registered via
 * NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#STATES_KEY}). Its contents are populated from
 * datapack JSONs at {@code data/<namespace>/modularshoot/states/<state_id>.json}
 * when a world is loaded and synced to clients on connect; it is
 * <strong>empty on the main menu</strong> (设计文档 §加载顺序). Every query
 * method therefore takes a {@link RegistryAccess} (or a {@link Level} that
 * provides one) so the caller supplies the correct runtime view.</p>
 *
 * <p>The framework pre-registers <strong>zero</strong> states. The
 * {@code modularshoot:ammo_damage_type} id is only a convention (设计文档
 * §ammo_damage_type 约定); upper-layer mods register it themselves when they
 * need it. This class provides only read access — Java write registration into
 * the dynamic registry is handled by the DataPackRegistry mechanism during
 * world load.</p>
 *
 * <p>Registry contents are hot-reloadable via {@code /reload}. All methods are
 * static utility methods; the class is not instantiable.</p>
 */
public final class StateRegistry {
    private StateRegistry() {
    }

    /**
     * Looks up a state definition by id in the {@code modularshoot:states}
     * registry.
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param stateId        the state definition id, e.g.
     *                       {@code examplemod:kill_count}
     * @return the matching {@link StateDefinition}, or {@code Optional.empty()}
     *         when the registry is absent or the id is not registered
     */
    public static Optional<StateDefinition> getState(RegistryAccess registryAccess, ResourceLocation stateId) {
        return registryAccess.registry(ModularShootRegistries.STATES_KEY)
                .flatMap(registry -> registry.getOptional(stateId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level   the world providing the {@link RegistryAccess}
     * @param stateId the state definition id
     * @return the matching {@link StateDefinition}, or {@code Optional.empty()}
     *         when the id is not registered
     */
    public static Optional<StateDefinition> getState(Level level, ResourceLocation stateId) {
        return getState(level.registryAccess(), stateId);
    }

    /**
     * Returns a stream of every registered state definition in the
     * {@code modularshoot:states} registry.
     *
     * @param registryAccess the runtime registry view
     * @return a stream of all {@link StateDefinition} entries; an empty stream
     *         when the registry is absent (e.g. on the main menu)
     */
    public static Stream<StateDefinition> getAllStates(RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.STATES_KEY)
                .map(Registry::stream)
                .orElse(Stream.empty());
    }

    /**
     * Checks whether a state definition is registered under the given id.
     *
     * @param registryAccess the runtime registry view
     * @param stateId        the state definition id to test
     * @return {@code true} if the id is registered; {@code false} when the
     *         registry is absent or the id is not registered
     */
    public static boolean isRegistered(RegistryAccess registryAccess, ResourceLocation stateId) {
        return registryAccess.registry(ModularShootRegistries.STATES_KEY)
                .map(registry -> registry.containsKey(stateId))
                .orElse(false);
    }
}
