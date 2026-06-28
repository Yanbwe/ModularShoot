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
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
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
 * <p>The {@link #createGunStack(ResourceLocation)} factory methods do
 * <strong>not</strong> query the registry, so they are safe to call before a
 * world is loaded (e.g. for creative tabs). The
 * {@link #createGunStack(ResourceLocation, RegistryAccess)} overloads
 * <em>do</em> query the registry to write the {@code ATTRIBUTE_MODIFIERS}
 * component, so they require a loaded world.</p>
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
     * generated random instance uuid and the {@code ATTRIBUTE_MODIFIERS}
     * component pre-populated from the resolved gun definition.
     *
     * <p>This overload queries the registry to look up the
     * {@link GunDefinition} and, when found, calls
     * {@link AttributeModifierService#applyModifiers} so the resulting stack
     * has non-zero base stats (fire_rate, hit_damage, etc.). When the
     * definition cannot be resolved (e.g. the id is not yet registered in the
     * supplied registry view), the stack is created with {@code gun_data} but
     * <strong>without</strong> the {@code ATTRIBUTE_MODIFIERS} component; the
     * caller must then call
     * {@link AttributeModifierService#refreshModifiers} once the definition is
     * available.</p>
     *
     * @param gunId          the gun definition id to bind to the stack
     * @param registryAccess the runtime registry view used to resolve the
     *                       gun definition and apply base attribute modifiers
     * @return a new {@link ItemStack} with {@code gun_data} set and, when the
     *         definition was found, {@code ATTRIBUTE_MODIFIERS} populated
     */
    public static ItemStack createGunStack(ResourceLocation gunId, RegistryAccess registryAccess) {
        return createGunStack(gunId, UUID.randomUUID(), registryAccess);
    }

    /**
     * Creates a gun {@link ItemStack} for the given gun id with a caller
     * supplied instance uuid and the {@code ATTRIBUTE_MODIFIERS} component
     * pre-populated from the resolved gun definition.
     *
     * <p>Intended for tests or other scenarios that require deterministic
     * uuids. Prefer
     * {@link #createGunStack(ResourceLocation, RegistryAccess)} for general
     * use.</p>
     *
     * <p>When the gun definition can be resolved from the supplied
     * {@link RegistryAccess}, {@link AttributeModifierService#applyModifiers}
     * is called to write the base attribute modifiers onto the stack. When the
     * definition is absent the stack is created without the
     * {@code ATTRIBUTE_MODIFIERS} component; the caller must then call
     * {@link AttributeModifierService#refreshModifiers} later.</p>
     *
     * @param gunId          the gun definition id to bind to the stack
     * @param instanceUuid   the per-stack instance uuid
     * @param registryAccess the runtime registry view used to resolve the
     *                       gun definition and apply base attribute modifiers
     * @return a new {@link ItemStack} with {@code gun_data} set and, when the
     *         definition was found, {@code ATTRIBUTE_MODIFIERS} populated
     */
    public static ItemStack createGunStack(
            ResourceLocation gunId, UUID instanceUuid, RegistryAccess registryAccess) {
        ItemStack stack = createGunStack(gunId, instanceUuid);
        Optional<GunDefinition> gunDef = getGun(registryAccess, gunId);
        if (gunDef.isPresent()) {
            AttributeModifierService.applyModifiers(stack, gunDef.get(), registryAccess);
        }
        return stack;
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
     * <p><strong>This method does NOT write the {@code ATTRIBUTE_MODIFIERS}
     * component.</strong> The resulting stack has the vanilla base of
     * {@code 0} for every attribute, which means {@code fire_rate = 0} and
     * the gun cannot fire until modifiers are applied. Callers MUST either:
     * <ul>
     *   <li>use {@link #createGunStack(ResourceLocation, RegistryAccess)}
     *       instead, which populates the component when the definition is
     *       resolvable; or</li>
     *   <li>call {@link AttributeModifierService#refreshModifiers} (or
     *       {@link AttributeModifierService#applyModifiers}) afterwards with
     *       a valid {@link RegistryAccess}.</li>
     * </ul>
     * Failing to do so yields a gun with zero stats (设计文档 §组件刷新时机).</p>
     *
     * @param gunId the gun definition id to bind to the stack
     * @return a new {@link ItemStack} with {@code gun_data} set but
     *         <strong>without</strong> {@code ATTRIBUTE_MODIFIERS}
     * @deprecated Use {@link #createGunStack(ResourceLocation, RegistryAccess)}
     *             to obtain a gun stack with attribute modifiers pre-populated,
     *             avoiding the fire_rate=0 pitfall for third-party callers.
     */
    @Deprecated
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
     * <p><strong>This method does NOT write the {@code ATTRIBUTE_MODIFIERS}
     * component.</strong> See {@link #createGunStack(ResourceLocation)} for
     * the full contract and the required follow-up call. Use
     * {@link #createGunStack(ResourceLocation, UUID, RegistryAccess)} when a
     * {@link RegistryAccess} is available.</p>
     *
     * @param gunId       the gun definition id to bind to the stack
     * @param instanceUuid the per-stack instance uuid
     * @return a new {@link ItemStack} with {@code gun_data} set but
     *         <strong>without</strong> {@code ATTRIBUTE_MODIFIERS}
     * @deprecated Use
     *             {@link #createGunStack(ResourceLocation, UUID, RegistryAccess)}
     *             to obtain a gun stack with attribute modifiers pre-populated.
     */
    @Deprecated
    public static ItemStack createGunStack(ResourceLocation gunId, UUID instanceUuid) {
        ItemStack stack = new ItemStack(ModularShootItems.GUN_ITEM.get());
        stack.set(ModularShootDataComponents.GUN_DATA.get(), GunData.create(gunId, instanceUuid));
        return stack;
    }
}
