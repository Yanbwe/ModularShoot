package org.yanbwe.modularshoot.registry.gun;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Query, factory and registration API for the {@code modularshoot:guns}
 * dynamic registry.
 *
 * <p>The gun registry is a datapack-driven dynamic registry registered via
 * NeoForge's {@code DataPackRegistryEvent} (see
 * {@link ModularShootRegistries#GUNS_KEY}). Its contents are populated from
 * datapack JSONs when a world is loaded and synced to clients on connect;
 * it is <strong>empty on the main menu</strong> (设计文档 §加载顺序). Every
 * query method therefore takes a {@link RegistryAccess} (or a {@link Level}
 * that provides one) so the caller supplies the correct runtime view.</p>
 *
 * <p>This class supports two registration paths (设计文档 §注册冲突与覆盖):
 * <ul>
 *   <li><b>Datapack JSON</b> &mdash; entries loaded from
 *       {@code data/<namespace>/modularshoot/guns/<id>.json} during world
 *       load or {@code /reload}. Handled automatically by NeoForge's
 *       {@code DataPackRegistryEvent}.</li>
 *   <li><b>Java API</b> &mdash; entries registered programmatically via
 *       {@link #registerGun} during mod initialisation. Java-API-registered
 *       ids take priority over datapack entries with the same id; the
 *       framework's {@link RegistrationCoordinator} tracks every claimed id
 *       so that later datapack loads cannot override them (设计文档
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
 * <p>The {@link #createGunStack} factory methods do <strong>not</strong>
 * query the registry, so they are safe to call before a world is loaded
 * (e.g. for creative tabs).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.</p>
 */
public final class GunRegistry {
    /**
     * Internal store of gun definitions registered via the Java API.
     *
     * <p>Keyed by gun id. Uses {@link ConcurrentHashMap} so that concurrent
     * reads from query methods safely observe writes from the mod-init
     * thread (设计文档 §注册表并发策略). Entries are stable for the
     * lifetime of the process and are merged with the datapack registry on
     * every query.</p>
     */
    private static final Map<ResourceLocation, GunDefinition> JAVA_API_GUNS =
            new ConcurrentHashMap<>();

    private GunRegistry() {
    }

    // ---- Java API registration ------------------------------------------

    /**
     * Registers a gun definition via the Java API.
     *
     * <p>Must be called during mod initialisation (before datapack loading
     * begins, i.e. in the mod constructor or
     * {@code FMLCommonSetupEvent}). The registered id is marked with
     * {@link RegistrationCoordinator#markJavaApiRegistered} so that any
     * later datapack JSON attempting to register the same id is rejected
     * with a {@code WARN} (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>Java-API-registered entries are kept in an internal map and merged
     * with the datapack registry on every query ({@link #getGun},
     * {@link #getAllGunIds}). They survive {@code /reload} because they are
     * not part of the per-world datapack registry instance.</p>
     *
     * <p>Re-registering the same id replaces the previous definition and
     * is otherwise a no-op (the {@link RegistrationCoordinator} mark is
     * idempotent).</p>
     *
     * @param gunId      the gun definition id, e.g.
     *                   {@code modularshoot:sniper_rifle}; must not be
     *                   {@code null}
     * @param definition the gun definition; must not be {@code null}
     */
    public static void registerGun(ResourceLocation gunId, GunDefinition definition) {
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(definition, "definition");
        JAVA_API_GUNS.put(gunId, definition);
        RegistrationCoordinator.markJavaApiRegistered(ModularShootRegistries.GUNS_KEY, gunId);
    }

    /**
     * Returns an unmodifiable snapshot of all gun definitions registered
     * via the Java API.
     *
     * <p>This is a read-only view of the internal Java-API store; it does
     * <strong>not</strong> include datapack-registered entries. Intended for
     * testing and for internal framework logic that needs to distinguish
     * Java-API entries from datapack entries.</p>
     *
     * @return an unmodifiable map of Java-API-registered gun ids to
     *         definitions; empty when no guns have been registered via the
     *         Java API
     */
    public static Map<ResourceLocation, GunDefinition> getJavaApiRegisteredGuns() {
        return Collections.unmodifiableMap(JAVA_API_GUNS);
    }

    // ---- Query methods --------------------------------------------------

    /**
     * Looks up a gun definition by id in the {@code modularshoot:guns}
     * registry.
     *
     * <p>Java-API-registered entries (via {@link #registerGun}) take
     * priority over datapack entries with the same id (设计文档
     * §注册冲突与覆盖, line 2289). When the id is present in both sources
     * the Java-API definition is returned.</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @param gunId          the gun definition id, e.g.
     *                       {@code modularshoot:sniper_rifle}
     * @return the matching {@link GunDefinition}, or {@code Optional.empty()}
     *         when the registry is absent or the id is not registered
     */
    public static Optional<GunDefinition> getGun(RegistryAccess registryAccess, ResourceLocation gunId) {
        final GunDefinition javaApiGun = JAVA_API_GUNS.get(gunId);
        if (javaApiGun != null) {
            return Optional.of(javaApiGun);
        }
        return registryAccess.registry(ModularShootRegistries.GUNS_KEY)
                .flatMap(registry -> registry.getOptional(gunId));
    }

    /**
     * Convenience overload that derives the registry view from a {@link Level}.
     *
     * @param level  the world providing the {@link RegistryAccess}
     * @param gunId  the gun definition id
     * @return the matching {@link GunDefinition}, or {@code Optional.empty()}
     *         when the id is not registered
     */
    public static Optional<GunDefinition> getGun(Level level, ResourceLocation gunId) {
        return getGun(level.registryAccess(), gunId);
    }

    /**
     * Returns every registered gun id in the {@code modularshoot:guns}
     * registry.
     *
     * <p>The returned set is the union of datapack-registered ids and
     * Java-API-registered ids (via {@link #registerGun}). When the same id
     * appears in both sources it is included only once (Java-API takes
     * priority at query time, see {@link #getGun}).</p>
     *
     * @param registryAccess the runtime registry view
     * @return an unmodifiable set of all gun ids; an empty set when the
     *         registry is absent (e.g. on the main menu) and no Java-API
     *         entries have been registered
     */
    public static Set<ResourceLocation> getAllGunIds(RegistryAccess registryAccess) {
        final Set<ResourceLocation> datapackIds = registryAccess.registry(ModularShootRegistries.GUNS_KEY)
                .map(Registry::keySet)
                .orElse(Set.of());
        if (JAVA_API_GUNS.isEmpty()) {
            return datapackIds;
        }
        final Set<ResourceLocation> allIds = new HashSet<>(datapackIds);
        allIds.addAll(JAVA_API_GUNS.keySet());
        return Collections.unmodifiableSet(allIds);
    }

    /**
     * Creates a gun {@link ItemStack} for the given gun id with a freshly
     * generated random instance uuid.
     *
     * <p>The stack is backed by the framework {@code modularshoot:gun} item
     * and carries a {@link GunData} component identifying the gun definition.
     * No registry lookup is performed, so this is safe to call before a world
     * is loaded (e.g. for creative tabs).</p>
     *
     * @param gunId the gun definition id to bind to the stack
     * @return a new {@link ItemStack} with {@code gun_data} set
     */
    public static ItemStack createGunStack(ResourceLocation gunId) {
        return createGunStack(gunId, UUID.randomUUID());
    }

    /**
     * Creates a gun {@link ItemStack} for the given gun id with a caller
     * supplied instance uuid.
     *
     * <p>Intended for tests or other scenarios that require deterministic
     * uuids. Prefer {@link #createGunStack(ResourceLocation)} for general
     * use.</p>
     *
     * @param gunId       the gun definition id to bind to the stack
     * @param instanceUuid the per-stack instance uuid
     * @return a new {@link ItemStack} with {@code gun_data} set
     */
    public static ItemStack createGunStack(ResourceLocation gunId, UUID instanceUuid) {
        ItemStack stack = new ItemStack(ModularShootItems.GUN_ITEM.get());
        stack.set(ModularShootDataComponents.GUN_DATA.get(), GunData.create(gunId, instanceUuid));
        return stack;
    }
}
