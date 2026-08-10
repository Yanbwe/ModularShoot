package org.yanbwe.modularshoot.registry.shooter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query and registration API for the {@code modularshoot:shooters}
 * dynamic registry.
 *
 * <p>The shooter registry is a datapack-driven dynamic registry registered
 * via NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#SHOOTERS_KEY}). Its contents are populated
 * from datapack JSONs when a world is loaded and synced to clients on
 * connect; it is <strong>empty on the main menu</strong> (设计文档 §加载顺序).
 * Every query method therefore takes a {@link RegistryAccess} (or a
 * {@link Level} that provides one) so the caller supplies the correct
 * runtime view.</p>
 *
 * <p>This class supports two registration paths (设计文档 §注册冲突与覆盖):
 * <ul>
 *   <li><b>Datapack JSON</b> &mdash; entries loaded from
 *       {@code data/<namespace>/modularshoot/shooters/<id>.json} during world
 *       load or {@code /reload}. Handled automatically by NeoForge's
 *       {@code DataPackRegistryEvent}.</li>
 *   <li><b>Java API</b> &mdash; entries registered programmatically via
 *       {@link #registerShooter} during mod initialisation. Java-API-
 *       registered ids take priority over datapack entries with the same id;
 *       the framework's {@link RegistrationCoordinator} tracks every claimed
 *       id so that later datapack loads cannot override them (设计文档
 *       §注册冲突与覆盖, line 2289).</li>
 * </ul>
 *
 * <p>Because the dynamic registry is frozen after world load, Java-API-
 * registered entries are kept in an internal map and merged with the
 * datapack registry on every query. From the caller's perspective both
 * sources share the same logical registry. Java-API entries survive
 * {@code /reload} because they are not part of the per-world datapack
 * registry instance (设计文档 §注册冲突与覆盖).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class ShooterRegistry {

    /**
     * Internal store of shooter definitions registered via the Java API.
     *
     * <p>Keyed by shooter id. Uses {@link ConcurrentHashMap} so that
     * concurrent reads from query methods safely observe writes from the
     * mod-init thread (设计文档 §注册表并发策略). Entries are stable for the
     * lifetime of the process and are merged with the datapack registry on
     * every query.</p>
     */
    private static final Map<ResourceLocation, ShooterDefinition> JAVA_API_SHOOTERS =
            new ConcurrentHashMap<>();

    private ShooterRegistry() {
    }

    // ---- Java API registration ------------------------------------------

    /**
     * Registers a shooter definition via the Java API.
     *
     * <p>Must be called during mod initialisation (before datapack loading
     * begins, i.e. in the mod constructor or
     * {@code FMLCommonSetupEvent}). The registered id is marked with
     * {@link RegistrationCoordinator#markJavaApiRegistered} so that any
     * later datapack JSON attempting to register the same id is rejected
     * with a {@code WARN} (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>Java-API-registered entries are kept in an internal map and merged
     * with the datapack registry on every query ({@link #getShooter},
     * {@link #getAllShooterIds}). They survive {@code /reload} because they
     * are not part of the per-world datapack registry instance.</p>
     *
     * <p>Re-registering the same id replaces the previous definition and is
     * otherwise a no-op (the {@link RegistrationCoordinator} mark is
     * idempotent).</p>
     *
     * @param shooterId  the shooter definition id, e.g.
     *                   {@code modularshoot:bone_shooter}; must not be
     *                   {@code null}
     * @param definition the shooter definition; must not be {@code null}
     */
    public static void registerShooter(ResourceLocation shooterId, ShooterDefinition definition) {
        Objects.requireNonNull(shooterId, "shooterId");
        Objects.requireNonNull(definition, "definition");
        JAVA_API_SHOOTERS.put(shooterId, definition);
        RegistrationCoordinator.markJavaApiRegistered(ModularShootRegistries.SHOOTERS_KEY, shooterId);
    }

    /**
     * Returns an unmodifiable snapshot of all shooter definitions registered
     * via the Java API.
     *
     * <p>This is a read-only view of the internal Java-API store; it does
     * <strong>not</strong> include datapack-registered entries. Intended for
     * testing and for internal framework logic that needs to distinguish
     * Java-API entries from datapack entries.</p>
     *
     * @return an unmodifiable map of Java-API-registered shooter ids to
     *         definitions; empty when no shooters have been registered via
     *         the Java API
     */
    public static Map<ResourceLocation, ShooterDefinition> getJavaApiRegisteredShooters() {
        return Collections.unmodifiableMap(JAVA_API_SHOOTERS);
    }

    // ---- Query methods --------------------------------------------------

    /**
     * Looks up a shooter definition by id in the
     * {@code modularshoot:shooters} registry.
     *
     * <p>Java-API-registered entries (via {@link #registerShooter}) take
     * priority over datapack entries with the same id (设计文档
     * §注册冲突与覆盖, line 2289). When the id is present in both sources the
     * Java-API definition is returned.</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param shooterId      the shooter definition id, e.g.
     *                       {@code modularshoot:bone_shooter}
     * @return the matching {@link ShooterDefinition}, or
     *         {@code Optional.empty()} when the registry is absent or the id
     *         is not registered
     */
    public static Optional<ShooterDefinition> getShooter(
            RegistryAccess registryAccess, ResourceLocation shooterId) {
        final ShooterDefinition javaApiShooter = JAVA_API_SHOOTERS.get(shooterId);
        if (javaApiShooter != null) {
            return Optional.of(javaApiShooter);
        }
        return registryAccess.registry(ModularShootRegistries.SHOOTERS_KEY)
                .flatMap(registry -> registry.getOptional(shooterId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level     the world providing the {@link RegistryAccess}
     * @param shooterId the shooter definition id
     * @return the matching {@link ShooterDefinition}, or
     *         {@code Optional.empty()} when the id is not registered
     */
    public static Optional<ShooterDefinition> getShooter(Level level, ResourceLocation shooterId) {
        return getShooter(level.registryAccess(), shooterId);
    }

    /**
     * Returns every registered shooter id in the
     * {@code modularshoot:shooters} registry.
     *
     * <p>The returned set is the union of datapack-registered ids and
     * Java-API-registered ids (via {@link #registerShooter}). When the same
     * id appears in both sources it is included only once (Java-API takes
     * priority at query time, see {@link #getShooter}).</p>
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all shooter ids; an empty set when the
     *         registry is absent (e.g. on the main menu) and no Java-API
     *         entries have been registered
     */
    public static Set<ResourceLocation> getAllShooterIds(RegistryAccess registryAccess) {
        final Set<ResourceLocation> datapackIds =
                registryAccess.registry(ModularShootRegistries.SHOOTERS_KEY)
                        .map(Registry::keySet)
                        .orElse(Set.of());
        if (JAVA_API_SHOOTERS.isEmpty()) {
            return datapackIds;
        }
        final Set<ResourceLocation> allIds = new HashSet<>(datapackIds);
        allIds.addAll(JAVA_API_SHOOTERS.keySet());
        return Collections.unmodifiableSet(allIds);
    }
}
