package org.yanbwe.modularshoot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginLockService;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.plugin.PluginUninstallService;
import org.yanbwe.modularshoot.plugin.PluginValidationService;
import org.yanbwe.modularshoot.plugin.PluginValidator;
import org.yanbwe.modularshoot.plugin.UninstallResult;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.shooting.ShootPredicate;
import org.yanbwe.modularshoot.shooting.ShootPredicateRegistry;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;

/**
 * Unified facade entry point for the ModularShoot plugin system.
 *
 * <p>This class is the single public API surface that other mods should use to
 * interact with the framework's plugin functionality. Every method is static
 * and delegates to the dedicated M2 service classes
 * ({@link PluginUninstallService}, {@link PluginLockService},
 * {@link PluginValidationService}, {@link PluginRegistry},
 * {@link PluginTypeRegistry}) or the M1 {@link GunRegistry}. No business logic
 * lives here; the facade only validates inputs at the boundary and forwards the
 * call to the appropriate service.</p>
 *
 * <p>The class is not instantiable. Callers reference methods directly, e.g.:</p>
 * <pre>{@code
 * ModularShootAPI.registerPluginValidator((gun, pluginId) -> ValidationResult.success());
 * ModularShootAPI.getGunId(gun);
 * ModularShootAPI.getInstalledPlugins(gun);
 * ModularShootAPI.uninstallPlugin(gun, uuid, player, false, true, registryAccess);
 * }</pre>
 *
 * <h2>Parameter validation</h2>
 * <p>Required (non-{@code null}) parameters are validated with
 * {@link Objects#requireNonNull} at this boundary so that downstream services
 * receive only well-formed inputs. The {@code player} parameter of the
 * uninstall family is explicitly {@link Nullable} and is therefore not
 * null-checked here; the underlying service handles a {@code null} player by
 * silently discarding returned items.</p>
 */
public final class ModularShootAPI {

    private ModularShootAPI() {
    }

    // ---- Plugin item queries --------------------------------------------

    /**
     * Checks whether the given stack is a framework {@code modularshoot:plugin}
     * item.
     *
     * <p>All plugin stacks share the single {@code modularshoot:plugin} item id;
     * the concrete plugin is selected by the {@code plugin_data} component. This
     * check tests the item type only, not the presence of the component.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a {@code modularshoot:plugin} item
     */
    public static boolean isPlugin(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.is(ModularShootItems.PLUGIN_ITEM.get());
    }

    /**
     * Reads the plugin definition id bound to a plugin stack.
     *
     * <p>Delegates to the {@code plugin_data} component carried by the stack.
     * Returns {@link Optional#empty()} when the stack is not a plugin item or
     * carries no {@code plugin_data} component.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return the plugin definition id, or empty when the stack is not a plugin
     *         or has no binding
     */
    public static Optional<ResourceLocation> getPluginId(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (!stack.is(ModularShootItems.PLUGIN_ITEM.get())) {
            return Optional.empty();
        }
        PluginData data = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        return data == null ? Optional.empty() : Optional.of(data.pluginId());
    }

    /**
     * Returns the {@link PluginData} component of a plugin stack.
     *
     * <p>Delegates to the {@code plugin_data} component carried by the stack.
     * Returns {@link Optional#empty()} when the stack is not a plugin item or
     * carries no {@code plugin_data} component.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return the {@link PluginData}, or empty when the stack is not a plugin
     *         or has no component
     */
    public static Optional<PluginData> getPluginData(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (!stack.is(ModularShootItems.PLUGIN_ITEM.get())) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.get(ModularShootDataComponents.PLUGIN_DATA.get()));
    }

    // ---- Gun plugin queries ---------------------------------------------

    /**
     * Returns the installed plugin list of a gun stack.
     *
     * <p>Delegates to {@link PluginUninstallService#getInstalledPlugins}. This
     * is a read-only query; it does not modify the stack and does not require a
     * {@link RegistryAccess}. The returned list is the immutable list stored in
     * the {@code gun_data} component (or an empty list when the stack is
     * invalid).</p>
     *
     * @param gun the gun item stack to inspect; must not be {@code null}
     * @return the immutable installed plugin list, or an empty list when the
     *         stack is not a gun or carries no {@code gun_data} component
     */
    public static List<PluginInstance> getInstalledPlugins(ItemStack gun) {
        Objects.requireNonNull(gun, "gun");
        return PluginUninstallService.getInstalledPlugins(gun);
    }

    // ---- Uninstall API --------------------------------------------------

    /**
     * Removes a single plugin identified by its instance uuid from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallPlugin}. See that method for the
     * full validation, event and item-return semantics.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param instanceUuid   the instance uuid of the plugin to remove; must not
     *                       be {@code null}
     * @param player         the player context for item return, or {@code null}
     *                       when triggered by a non-player source
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return the removed plugin as an item
     *                       stack to {@code player} (when non-null)
     * @param registryAccess the runtime registry view (for modifier refresh);
     *                       must not be {@code null}
     * @return an {@link UninstallResult} describing the outcome
     */
    public static UninstallResult uninstallPlugin(
            ItemStack gun,
            UUID instanceUuid,
            @Nullable Player player,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(instanceUuid, "instanceUuid");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginUninstallService.uninstallPlugin(
                gun, instanceUuid, player, force, returnItems, registryAccess);
    }

    /**
     * Removes one randomly chosen uninstallable plugin from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallRandomPlugin}. See that method for
     * the candidate selection and outcome semantics.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param player         the player context for item return, or {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return the removed plugin item
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return an {@link UninstallResult} describing the outcome
     */
    public static UninstallResult uninstallRandomPlugin(
            ItemStack gun,
            @Nullable Player player,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginUninstallService.uninstallRandomPlugin(
                gun, player, force, returnItems, registryAccess);
    }

    /**
     * Removes every plugin whose {@code installedTypeId} matches the given
     * category id from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallPluginsByType}. See that method
     * for the snapshot and per-uuid removal semantics.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param player         the player context for item return, or {@code null}
     * @param pluginTypeId   the category id to match against; must not be
     *                       {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return each removed plugin item
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return a list of {@link UninstallResult}, one per matching plugin;
     *         empty when the stack is invalid or no plugin matches
     */
    public static List<UninstallResult> uninstallPluginsByType(
            ItemStack gun,
            @Nullable Player player,
            ResourceLocation pluginTypeId,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(pluginTypeId, "pluginTypeId");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginUninstallService.uninstallPluginsByType(
                gun, player, pluginTypeId, force, returnItems, registryAccess);
    }

    /**
     * Removes every installed plugin from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallAllPlugins}. See that method for
     * the snapshot and per-uuid removal semantics.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param player         the player context for item return, or {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return each removed plugin item
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return a list of {@link UninstallResult}, one per installed plugin;
     *         empty when the stack is invalid or has no plugins
     */
    public static List<UninstallResult> uninstallAllPlugins(
            ItemStack gun,
            @Nullable Player player,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginUninstallService.uninstallAllPlugins(
                gun, player, force, returnItems, registryAccess);
    }

    // ---- Lock API -------------------------------------------------------

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack.
     *
     * <p>Delegates to {@link PluginLockService#setPluginLocked}. The operation
     * is a no-op when the stack is not a gun, carries no gun data, the plugin is
     * absent, or the plugin is already in the requested lock state.</p>
     *
     * @param gun          the gun item stack to modify (mutated on success);
     *                     must not be {@code null}
     * @param instanceUuid the instance uuid of the plugin to lock/unlock; must
     *                     not be {@code null}
     * @param locked       {@code true} to lock, {@code false} to unlock
     */
    public static void setPluginLocked(ItemStack gun, UUID instanceUuid, boolean locked) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(instanceUuid, "instanceUuid");
        PluginLockService.setPluginLocked(gun, instanceUuid, locked);
    }

    /**
     * Queries whether a specific installed plugin instance is locked.
     *
     * <p>Delegates to {@link PluginLockService#isPluginLocked}. Returns
     * {@code false} when the stack is not a gun, carries no gun data, the plugin
     * is absent, or the plugin is unlocked.</p>
     *
     * @param gun          the gun item stack to inspect; must not be {@code null}
     * @param instanceUuid the instance uuid of the plugin to query; must not be
     *                     {@code null}
     * @return {@code true} if the plugin exists and is locked; {@code false}
     *         otherwise
     */
    public static boolean isPluginLocked(ItemStack gun, UUID instanceUuid) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(instanceUuid, "instanceUuid");
        return PluginLockService.isPluginLocked(gun, instanceUuid);
    }

    // ---- Custom validators ----------------------------------------------

    /**
     * Registers a custom plugin installation validator.
     *
     * <p>Delegates to
     * {@link PluginValidationService#registerPluginValidator}. Registered
     * validators run after the framework default validation passes; the first
     * validator that returns a failing {@code ValidationResult} aborts the
     * installation. Safe to call during mod common-setup.</p>
     *
     * @param validator the validator to register; must not be {@code null}
     */
    public static void registerPluginValidator(PluginValidator validator) {
        Objects.requireNonNull(validator, "validator");
        PluginValidationService.registerPluginValidator(validator);
    }

    // ---- Registry queries -----------------------------------------------

    /**
     * Looks up a plugin definition by id in the
     * {@code modularshoot:plugins} registry.
     *
     * <p>Delegates to {@link PluginRegistry#getPlugin}. The registry is
     * datapack-driven and empty on the main menu, so a {@link RegistryAccess}
     * from a loaded world is required.</p>
     *
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @param pluginId       the plugin definition id; must not be {@code null}
     * @return the matching {@link PluginDefinition}, or empty when the registry
     *         is absent or the id is not registered
     */
    public static Optional<PluginDefinition> getPluginDefinition(
            RegistryAccess registryAccess, ResourceLocation pluginId) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        Objects.requireNonNull(pluginId, "pluginId");
        return PluginRegistry.getPlugin(registryAccess, pluginId);
    }

    /**
     * Looks up a plugin type definition by id in the
     * {@code modularshoot:plugin_types} registry.
     *
     * <p>Delegates to {@link PluginTypeRegistry#getPluginType}. The registry is
     * datapack-driven and empty on the main menu, so a {@link RegistryAccess}
     * from a loaded world is required.</p>
     *
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @param pluginTypeId   the plugin type id; must not be {@code null}
     * @return the matching {@link PluginTypeDefinition}, or empty when the
     *         registry is absent or the id is not registered
     */
    public static Optional<PluginTypeDefinition> getPluginTypeDefinition(
            RegistryAccess registryAccess, ResourceLocation pluginTypeId) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        Objects.requireNonNull(pluginTypeId, "pluginTypeId");
        return PluginTypeRegistry.getPluginType(registryAccess, pluginTypeId);
    }

    /**
     * Looks up a gun definition by id in the {@code modularshoot:guns}
     * registry.
     *
     * <p>Delegates to {@link GunRegistry#getGun}. The registry is
     * datapack-driven and empty on the main menu, so a {@link RegistryAccess}
     * from a loaded world is required.</p>
     *
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @param gunId          the gun definition id; must not be {@code null}
     * @return the matching {@link GunDefinition}, or empty when the registry is
     *         absent or the id is not registered
     */
    public static Optional<GunDefinition> getGunDefinition(
            RegistryAccess registryAccess, ResourceLocation gunId) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        Objects.requireNonNull(gunId, "gunId");
        return GunRegistry.getGun(registryAccess, gunId);
    }

    // ---- Gun queries ----------------------------------------------------

    /**
     * Reads the gun definition id bound to a gun stack.
     *
     * <p>Reads the {@code gun_data} component carried by the stack and returns
     * its {@code gunId}. Returns {@code null} when the stack is not a
     * {@code modularshoot:gun} item or carries no {@code gun_data} component.</p>
     *
     * @param gun the gun item stack to inspect; must not be {@code null}
     * @return the gun definition id, or {@code null} when the stack is not a gun
     *         or has no gun data
     */
    @Nullable
    public static ResourceLocation getGunId(ItemStack gun) {
        Objects.requireNonNull(gun, "gun");
        if (!gun.is(ModularShootItems.GUN_ITEM.get())) {
            return null;
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunId();
    }

    /**
     * Checks whether the given stack is a framework {@code modularshoot:gun}
     * item.
     *
     * <p>Tests the item type only, not the presence of the {@code gun_data}
     * component. Used by the shooting engine to decide whether to intercept
     * vanilla left-click attacks.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a {@code modularshoot:gun} item
     */
    public static boolean isGun(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return stack.is(ModularShootItems.GUN_ITEM.get());
    }

    // ---- Shoot predicates ------------------------------------------------

    /**
     * Registers a custom shoot predicate that is evaluated on every shot
     * after fire-rate control passes.
     *
     * <p>Delegates to {@link ShootPredicateRegistry#register}. The first
     * predicate that returns a failing result aborts the shot and displays
     * the failure reason to the player on the action bar. Safe to call during
     * mod common-setup; the framework registers zero predicates by default
     * (设计文档 §射击条件判断).</p>
     *
     * @param predicate the predicate to register; must not be {@code null}
     */
    public static void registerShootPredicate(ShootPredicate predicate) {
        Objects.requireNonNull(predicate, "predicate");
        ShootPredicateRegistry.register(predicate);
    }

    // ---- Trait hooks -----------------------------------------------------

    /**
     * Registers a runtime hook callback for a trait id and hook type.
     *
     * <p>Delegates to {@link TraitHookRegistry#register}. The callback must
     * implement the interface that corresponds to {@code type} (e.g. an
     * {@link TraitHookType#ON_TICK} registration must pass a
     * {@link TraitCallbacks.TraitTickCallback}). Multiple callbacks for the
     * same trait id and hook type are stored and fired in registration order
     * (设计文档 §特性钩子注册 API).</p>
     *
     * @param traitId  the trait definition id; must not be {@code null}
     * @param type     the hook type to attach the callback to; must not be
     *                 {@code null}
     * @param callback the callback to register; must not be {@code null} and
     *                 must implement the interface expected for {@code type}
     * @param <T>      the callback interface type, inferred from
     *                 {@code callback}
     */
    public static <T extends TraitCallbacks.TraitCallback> void registerTraitHook(
            ResourceLocation traitId, TraitHookType type, T callback) {
        Objects.requireNonNull(traitId, "traitId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
        TraitHookRegistry.register(traitId, type, callback);
    }
}
