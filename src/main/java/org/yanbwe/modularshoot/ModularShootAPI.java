package org.yanbwe.modularshoot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginExtraValueService;
import org.yanbwe.modularshoot.plugin.PluginInstallService;
import org.yanbwe.modularshoot.plugin.PluginLockService;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.plugin.PluginUninstallService;
import org.yanbwe.modularshoot.plugin.PluginValidationService;
import org.yanbwe.modularshoot.plugin.PluginValidator;
import org.yanbwe.modularshoot.plugin.UninstallResult;
import org.yanbwe.modularshoot.registry.binding.GunItemBinding;
import org.yanbwe.modularshoot.registry.binding.GunItemBindingRegistry;
import org.yanbwe.modularshoot.registry.binding.PluginItemBinding;
import org.yanbwe.modularshoot.registry.binding.PluginItemBindingRegistry;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.damage.DamageHandler;
import org.yanbwe.modularshoot.damage.DamageHandlerRegistry;
import org.yanbwe.modularshoot.shooting.ShootEffect;
import org.yanbwe.modularshoot.shooting.ShootEffectRegistry;
import org.yanbwe.modularshoot.shooting.ShootPredicate;
import org.yanbwe.modularshoot.shooting.ShootPredicateRegistry;
import org.yanbwe.modularshoot.state.GunState;
import org.yanbwe.modularshoot.state.PlayerState;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;
import org.yanbwe.modularshoot.util.GunResolver;
import org.yanbwe.modularshoot.variant.VariantContributor;
import org.yanbwe.modularshoot.variant.VariantContributorRegistry;

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
 * ModularShootAPI.registerPluginValidator(
 *         (player, gun, pluginId, registryAccess) -> ValidationResult.success());
 * ModularShootAPI.getGunId(gun);
 * ModularShootAPI.getInstalledPlugins(gun);
 * ModularShootAPI.uninstallPlugin(gun, uuid, player, false, true);
 * }</pre>
 *
 * <h2>Parameter validation</h2>
 * <p>Required (non-{@code null}) parameters are validated with
 * {@link Objects#requireNonNull} at this boundary so that downstream services
 * receive only well-formed inputs. The {@code player} parameter of the
 * uninstall family is explicitly {@link Nullable} and is therefore not
 * null-checked here; the underlying service handles a {@code null} player by
 * silently discarding returned items. In contrast,
 * {@link #installPlugin(ItemStack, ItemStack, Player)} requires a non-null
 * player because installation needs the player's random source and the
 * runtime {@link RegistryAccess} derived from {@code player.level()}.</p>
 */
public final class ModularShootAPI {

    private ModularShootAPI() {
    }

    // ---- Plugin item queries --------------------------------------------

    /**
     * Checks whether the given stack is a plugin (设计规格 物品绑定系统 §4.1).
     *
     * <p>Dual-channel recognition with the <b>component channel first</b> and
     * the <b>binding channel as fallback</b>: a stack is a plugin when it
     * carries the {@code plugin_data} component, or when its item id is bound
     * to a plugin entry via the {@code modularshoot:plugin_items} binding
     * table (Java API channel, see {@link PluginItemBindingRegistry}). This
     * is the <b>degraded overload</b> for callers without a registry view
     * (e.g. the main menu): it checks the component channel plus the
     * Java-API binding channel only — the datapack binding channel is
     * skipped because {@link RegistryAccess#EMPTY} never contains the
     * datapack registries (semantically equivalent to "Java-API bindings
     * only"). Runtime callers should prefer
     * {@link #isPlugin(ItemStack, RegistryAccess)}.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a plugin via either channel
     */
    public static boolean isPlugin(ItemStack stack) {
        return isPlugin(stack, RegistryAccess.EMPTY, false);
    }

    /**
     * Checks whether the given stack is a plugin (设计规格 物品绑定系统 §4.1).
     *
     * <p>Dual-channel recognition with the <b>component channel first</b> and
     * the <b>binding channel as fallback</b>: a stack is a plugin when it
     * carries the {@code plugin_data} component, or when its item id is bound
     * to a plugin entry via the {@code modularshoot:plugin_items} binding
     * table (Java API plus datapack channels, see
     * {@link PluginItemBindingRegistry}). The datapack channel requires the
     * runtime {@link RegistryAccess} of a loaded world; supply
     * {@code player.level().registryAccess()} or equivalent.</p>
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return {@code true} when the stack is a plugin via either channel
     */
    public static boolean isPlugin(ItemStack stack, RegistryAccess access) {
        return isPlugin(stack, access, true);
    }

    /**
     * Resolves the plugin definition id of a plugin stack (设计规格 物品绑定系统
     * §4.1).
     *
     * <p>Dual-channel resolution: the <b>component channel is checked
     * first</b> — a {@code plugin_data} component yields its
     * {@link PluginData#pluginId()}; when no component is present, the
     * binding channel resolves the plugin id from the
     * {@code modularshoot:plugin_items} binding table (Java API plus
     * datapack). Returns {@link Optional#empty()} when neither channel
     * identifies a plugin.</p>
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return the plugin definition id, or empty when the stack is not a
     *         plugin
     */
    public static Optional<ResourceLocation> resolvePluginId(
            ItemStack stack, RegistryAccess access) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        PluginData pluginData = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        if (pluginData != null) {
            return Optional.of(pluginData.pluginId());
        }
        return findBoundPluginId(stack, access, true);
    }

    /**
     * Reads the plugin definition id bound to a plugin stack.
     *
     * <p>Delegates to the {@code plugin_data} component carried by the stack;
     * the component is read directly from any stack without an item-id
     * precheck (设计规格 物品绑定系统 §4.2). Returns
     * {@link Optional#empty()} when the stack carries no {@code plugin_data}
     * component — binding-table plugins get the component lazily attached on
     * first use (设计规格 物品绑定系统 §5), so an empty result before
     * attachment is expected.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return the plugin definition id, or empty when the stack has no
     *         component
     */
    public static Optional<ResourceLocation> getPluginId(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        PluginData data = stack.get(ModularShootDataComponents.PLUGIN_DATA.get());
        return data == null ? Optional.empty() : Optional.of(data.pluginId());
    }

    /**
     * Returns the {@link PluginData} component of a plugin stack.
     *
     * <p>Delegates to the {@code plugin_data} component carried by the stack;
     * the component is read directly from any stack without an item-id
     * precheck (设计规格 物品绑定系统 §4.2). Returns {@link Optional#empty()}
     * when the stack carries no {@code plugin_data} component — binding-table
     * plugins get the component lazily attached on first use (设计规格 物品绑定
     * 系统 §5), so an empty result before attachment is expected.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return the {@link PluginData}, or empty when the stack has no component
     */
    public static Optional<PluginData> getPluginData(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
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

    /**
     * Returns the total {@code extra_values} of a gun stack: the gun
     * definition's base values plus the accumulated extra values of every
     * valid plugin installed on it, summed per namespaced key.
     *
     * <p>Delegates to {@link PluginExtraValueService#aggregate}. The
     * framework never interprets these values (e.g. a rarity core may sum
     * {@code "raritycore:rarity"}) — it only carries and aggregates them.
     * The gun definition is resolved via {@link GunRegistry} from the
     * supplied registry view; when the gun definition is missing (degraded)
     * its base values are omitted — the same degradation contract as the
     * plugin pipeline. Degraded plugins (definition missing from the
     * datapack registry) are filtered likewise. Keys never declared by the
     * gun definition or any installed plugin stay absent from the result;
     * use {@link #getExtraValue} for a defaulted single-key lookup.</p>
     *
     * <p>Requires a loaded world's {@link RegistryAccess} because the
     * {@code modularshoot:plugins} registry is datapack-driven (empty on the
     * main menu); supply {@code player.level().registryAccess()} or
     * equivalent.</p>
     *
     * @param gun            the gun item stack to inspect; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view; must not be
     *                       {@code null}
     * @return an immutable map of key &rarr; total sum; empty when the
     *         stack is not a gun or neither the gun definition nor any
     *         valid plugin declares any extra value
     */
    public static Map<ResourceLocation, Double> getExtraValueSums(
            ItemStack gun, RegistryAccess registryAccess) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginExtraValueService.aggregate(gun, registryAccess);
    }

    /**
     * Convenience single-key lookup over a gun's total {@code extra_values}
     * (gun definition base plus accumulated installed-plugin sums): the
     * value of {@code key}, or {@code 0.0} when the key is undeclared.
     *
     * @param gun            the gun item stack to inspect; must not be
     *                       {@code null}
     * @param key            the extra-value key to look up; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view; must not be
     *                       {@code null}
     * @return the accumulated value of {@code key}, or {@code 0.0} when
     *         undeclared
     */
    public static double getExtraValue(
            ItemStack gun, ResourceLocation key, RegistryAccess registryAccess) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginExtraValueService.get(gun, key, registryAccess);
    }

    // ---- Uninstall API --------------------------------------------------

    /**
     * Removes a single plugin identified by its instance uuid from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallPlugin}. See that method for the
     * full validation, event and item-return semantics.</p>
     *
     * <p>The runtime {@link RegistryAccess} required for modifier refresh and
     * the degradation check is derived internally from
     * {@code player.level().registryAccess()}, so the caller does not need to
     * obtain one explicitly (设计文档 §API 签名 — 无 RegistryAccess 参数).
     * When the player context may be {@code null} (e.g. the gun is in a chest
     * or processed by automation), use the
     * {@link #uninstallPlugin(ItemStack, UUID, Player, boolean, boolean, RegistryAccess)}
     * overload instead.</p>
     *
     * @param gun          the gun item stack to modify (mutated on success);
     *                     must not be {@code null}
     * @param instanceUuid the instance uuid of the plugin to remove; must not
     *                     be {@code null}
     * @param player       the player context for item return and registry
     *                     resolution; must not be {@code null}
     * @param force        {@code true} to ignore the {@code locked} flag
     * @param returnItems  {@code true} to return the removed plugin as an item
     *                     stack to {@code player}
     * @return an {@link UninstallResult} describing the outcome
     */
    public static UninstallResult uninstallPlugin(
            ItemStack gun,
            UUID instanceUuid,
            Player player,
            boolean force,
            boolean returnItems
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(instanceUuid, "instanceUuid");
        Objects.requireNonNull(player, "player");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginUninstallService.uninstallPlugin(
                gun, instanceUuid, player, force, returnItems, registryAccess);
    }

    /**
     * Removes a single plugin identified by its instance uuid from a gun stack,
     * with an explicit {@link RegistryAccess}.
     *
     * <p>This overload is retained for callers that already hold a
     * {@link RegistryAccess} or need a {@code null} player context (e.g. the
     * gun is in a chest or processed by automation). New callers should prefer
     * {@link #uninstallPlugin(ItemStack, UUID, Player, boolean, boolean)}
     * which derives the registry view from the player and matches the design
     * document signature (设计文档 §API 签名).</p>
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
     * @deprecated Use {@link #uninstallPlugin(ItemStack, UUID, Player, boolean, boolean)}
     *             instead; this overload will be removed in a future release.
     */
    @Deprecated
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
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, use
     * the
     * {@link #uninstallRandomPlugin(ItemStack, Player, boolean, boolean, RegistryAccess)}
     * overload instead.</p>
     *
     * @param gun         the gun item stack to modify (mutated on success);
     *                    must not be {@code null}
     * @param player      the player context for item return and registry
     *                    resolution; must not be {@code null}
     * @param force       {@code true} to ignore the {@code locked} flag
     * @param returnItems {@code true} to return the removed plugin item
     * @return an {@link UninstallResult} describing the outcome
     */
    public static UninstallResult uninstallRandomPlugin(
            ItemStack gun,
            Player player,
            boolean force,
            boolean returnItems
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(player, "player");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginUninstallService.uninstallRandomPlugin(
                gun, player, force, returnItems, registryAccess);
    }

    /**
     * Removes one randomly chosen uninstallable plugin from a gun stack, with
     * an explicit {@link RegistryAccess}.
     *
     * <p>This overload is retained for callers that already hold a
     * {@link RegistryAccess} or need a {@code null} player context. New
     * callers should prefer
     * {@link #uninstallRandomPlugin(ItemStack, Player, boolean, boolean)}
     * which derives the registry view from the player (设计文档 §API 签名).</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param player         the player context for item return, or {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return the removed plugin item
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return an {@link UninstallResult} describing the outcome
     * @deprecated Use {@link #uninstallRandomPlugin(ItemStack, Player, boolean, boolean)}
     *             instead; this overload will be removed in a future release.
     */
    @Deprecated
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
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, use
     * the
     * {@link #uninstallPluginsByType(ItemStack, Player, ResourceLocation, boolean, boolean, RegistryAccess)}
     * overload instead.</p>
     *
     * @param gun          the gun item stack to modify (mutated on success);
     *                     must not be {@code null}
     * @param player       the player context for item return and registry
     *                     resolution; must not be {@code null}
     * @param pluginTypeId the category id to match against; must not be
     *                     {@code null}
     * @param force        {@code true} to ignore the {@code locked} flag
     * @param returnItems  {@code true} to return each removed plugin item
     * @return a list of {@link UninstallResult}, one per matching plugin;
     *         empty when the stack is invalid or no plugin matches
     */
    public static List<UninstallResult> uninstallPluginsByType(
            ItemStack gun,
            Player player,
            ResourceLocation pluginTypeId,
            boolean force,
            boolean returnItems
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(pluginTypeId, "pluginTypeId");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginUninstallService.uninstallPluginsByType(
                gun, player, pluginTypeId, force, returnItems, registryAccess);
    }

    /**
     * Removes every plugin whose {@code installedTypeId} matches the given
     * category id from a gun stack, with an explicit {@link RegistryAccess}.
     *
     * <p>This overload is retained for callers that already hold a
     * {@link RegistryAccess} or need a {@code null} player context. New
     * callers should prefer
     * {@link #uninstallPluginsByType(ItemStack, Player, ResourceLocation, boolean, boolean)}
     * which derives the registry view from the player (设计文档 §API 签名).</p>
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
     * @deprecated Use {@link #uninstallPluginsByType(ItemStack, Player, ResourceLocation, boolean, boolean)}
     *             instead; this overload will be removed in a future release.
     */
    @Deprecated
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
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, use
     * the
     * {@link #uninstallAllPlugins(ItemStack, Player, boolean, boolean, RegistryAccess)}
     * overload instead.</p>
     *
     * @param gun         the gun item stack to modify (mutated on success);
     *                    must not be {@code null}
     * @param player      the player context for item return and registry
     *                    resolution; must not be {@code null}
     * @param force       {@code true} to ignore the {@code locked} flag
     * @param returnItems {@code true} to return each removed plugin item
     * @return a list of {@link UninstallResult}, one per installed plugin;
     *         empty when the stack is invalid or has no plugins
     */
    public static List<UninstallResult> uninstallAllPlugins(
            ItemStack gun,
            Player player,
            boolean force,
            boolean returnItems
    ) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(player, "player");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginUninstallService.uninstallAllPlugins(
                gun, player, force, returnItems, registryAccess);
    }

    /**
     * Removes every installed plugin from a gun stack, with an explicit
     * {@link RegistryAccess}.
     *
     * <p>This overload is retained for callers that already hold a
     * {@link RegistryAccess} or need a {@code null} player context. New
     * callers should prefer
     * {@link #uninstallAllPlugins(ItemStack, Player, boolean, boolean)}
     * which derives the registry view from the player (设计文档 §API 签名).</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param player         the player context for item return, or {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return each removed plugin item
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @return a list of {@link UninstallResult}, one per installed plugin;
     *         empty when the stack is invalid or has no plugins
     * @deprecated Use {@link #uninstallAllPlugins(ItemStack, Player, boolean, boolean)}
     *             instead; this overload will be removed in a future release.
     */
    @Deprecated
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

    // ---- Install API ----------------------------------------------------

    /**
     * Programmatically installs a plugin (automation, admin tools, quest
     * rewards, etc.).
     *
     * <p>Delegates to
     * {@link PluginInstallService#installPlugin(ItemStack, ItemStack, Player, RegistryAccess)};
     * the player must be non-null (installation needs the random source and
     * the runtime {@link RegistryAccess}, both derived from
     * {@code player.level()}). The result carries the modified gun copy and
     * the consumed plugin copy; the caller is responsible for writing them
     * back to the container/inventory (following the container-right-click
     * install path's {@code slot.set} pattern). The input stacks are never
     * mutated (设计文档 §系统四 安装交互 — Apotheosis 不可变风格).</p>
     *
     * @param gun         the target gun item stack (not modified)
     * @param pluginStack the plugin item stack carrying {@code PluginData}
     *                    (not modified)
     * @param player      the player performing the installation; must not be
     *                    {@code null}
     * @return an {@link PluginInstallService.InstallResult}; on success it
     *         carries the modified copies
     * @throws NullPointerException when any parameter is {@code null}
     *                              (consistent with the other facade methods)
     */
    public static PluginInstallService.InstallResult installPlugin(
            ItemStack gun, ItemStack pluginStack, Player player) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(pluginStack, "pluginStack");
        Objects.requireNonNull(player, "player");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginInstallService.installPlugin(gun, pluginStack, player, registryAccess);
    }

    // ---- Lock API -------------------------------------------------------

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack,
     * then refreshes the {@code ATTRIBUTE_MODIFIERS} component.
     *
     * <p>Delegates to
     * {@link PluginLockService#setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)}.
     * The operation is a no-op when the stack is not a gun, carries no gun
     * data, the plugin is absent, or the plugin is already in the requested
     * lock state. This is the preferred overload: it honours the design's
     * refresh-trigger-point list (设计文档 §组件刷新时机).</p>
     *
     * @param gun            the gun item stack to modify (mutated on success);
     *                       must not be {@code null}
     * @param instanceUuid   the instance uuid of the plugin to lock/unlock;
     *                       must not be {@code null}
     * @param locked         {@code true} to lock, {@code false} to unlock
     * @param registryAccess the runtime registry view used to refresh
     *                       attribute modifiers after the update; must not be
     *                       {@code null}
     */
    public static void setPluginLocked(
            ItemStack gun, UUID instanceUuid, boolean locked, RegistryAccess registryAccess) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(instanceUuid, "instanceUuid");
        Objects.requireNonNull(registryAccess, "registryAccess");
        PluginLockService.setPluginLocked(gun, instanceUuid, locked, registryAccess);
    }

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack
     * without refreshing the {@code ATTRIBUTE_MODIFIERS} component.
     *
     * <p>Delegates to {@link PluginLockService#setPluginLocked}. The operation
     * is a no-op when the stack is not a gun, carries no gun data, the plugin is
     * absent, or the plugin is already in the requested lock state.</p>
     *
     * <p>Because a lock-state change does not alter which modifiers are active,
     * skipping the refresh is safe in practice. Callers that have a
     * {@link RegistryAccess} should prefer
     * {@link #setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)} to
     * fully honour the design's refresh-trigger-point list.</p>
     *
     * @param gun          the gun item stack to modify (mutated on success);
     *                     must not be {@code null}
     * @param instanceUuid the instance uuid of the plugin to lock/unlock; must
     *                     not be {@code null}
     * @param locked       {@code true} to lock, {@code false} to unlock
     * @deprecated Use {@link #setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)}
     *             to ensure the {@code ATTRIBUTE_MODIFIERS} component is
     *             refreshed after the lock change.
     */
    @Deprecated
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
     * Registers a gun definition via the Java API.
     *
     * <p>Delegates to {@link GunRegistry#registerGun}. Must be called during
     * mod initialisation (before datapack loading begins, i.e. in the mod
     * constructor or {@code FMLCommonSetupEvent}). The registered id is
     * marked with {@link RegistrationCoordinator#markJavaApiRegistered} so
     * that any later datapack JSON attempting to register the same id is
     * rejected with a {@code WARN} (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>Java-API-registered entries survive {@code /reload} and take
     * priority over datapack entries with the same id. See
     * {@link GunRegistry#registerGun} for full semantics.</p>
     *
     * @param gunId      the gun definition id, e.g.
     *                   {@code modularshoot:sniper_rifle}; must not be
     *                   {@code null}
     * @param definition the gun definition; must not be {@code null}
     */
    public static void registerGun(ResourceLocation gunId, GunDefinition definition) {
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(definition, "definition");
        GunRegistry.registerGun(gunId, definition);
    }

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
     * <p>Delegates to the {@code gun_data} component carried by the stack;
     * the component is read directly from any stack without an item-id
     * precheck (设计规格 物品绑定系统 §4.2). Returns {@code null} when the
     * stack carries no {@code gun_data} component — binding-table guns get
     * the component lazily attached on first use (设计规格 物品绑定系统 §5),
     * so a {@code null} result before attachment is expected.</p>
     *
     * @param gun the stack to inspect; must not be {@code null}
     * @return the gun definition id, or {@code null} when the stack has no
     *         component
     */
    @Nullable
    public static ResourceLocation getGunId(ItemStack gun) {
        Objects.requireNonNull(gun, "gun");
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? null : gunData.gunId();
    }

    /**
     * Returns the {@link GunData} component of a gun stack.
     *
     * <p>Delegates to the {@code gun_data} component carried by the stack;
     * the component is read directly from any stack without an item-id
     * precheck (设计规格 物品绑定系统 §4.2). Returns {@link Optional#empty()}
     * when the stack carries no {@code gun_data} component — binding-table
     * guns get the component lazily attached on first use (设计规格 物品绑定
     * 系统 §5), so an empty result before attachment is expected. Upper-layer
     * mods can use this to access the full per-gun runtime state (gun id,
     * instance uuid, installed plugins, modifier version, state payload)
     * rather than only the gun id exposed by {@link #getGunId}.</p>
     *
     * @param gun the stack to inspect; must not be {@code null}
     * @return the {@link GunData}, or empty when the stack has no component
     */
    public static Optional<GunData> getGunData(ItemStack gun) {
        Objects.requireNonNull(gun, "gun");
        return Optional.ofNullable(gun.get(ModularShootDataComponents.GUN_DATA.get()));
    }

    /**
     * Checks whether the given stack is a gun (设计规格 物品绑定系统 §4.1).
     *
     * <p>Dual-channel recognition with the <b>component channel first</b> and
     * the <b>binding channel as fallback</b>: a stack is a gun when it
     * carries the {@code gun_data} component, or when its item id is bound
     * to a gun entry via the {@code modularshoot:gun_items} binding table
     * (Java API channel, see {@link GunItemBindingRegistry}). This is the
     * <b>degraded overload</b> for callers without a registry view (e.g. the
     * main menu): it checks the component channel plus the Java-API binding
     * channel only — the datapack binding channel is skipped because
     * {@link RegistryAccess#EMPTY} never contains the datapack registries
     * (semantically equivalent to "Java-API bindings only"). Runtime callers
     * should prefer {@link #isGun(ItemStack, RegistryAccess)}.</p>
     *
     * @param stack the stack to inspect; must not be {@code null}
     * @return {@code true} when the stack is a gun via either channel
     */
    public static boolean isGun(ItemStack stack) {
        return isGun(stack, RegistryAccess.EMPTY, false);
    }

    /**
     * Checks whether the given stack is a gun (设计规格 物品绑定系统 §4.1).
     *
     * <p>Dual-channel recognition with the <b>component channel first</b> and
     * the <b>binding channel as fallback</b>: a stack is a gun when it
     * carries the {@code gun_data} component, or when its item id is bound
     * to a gun entry via the {@code modularshoot:gun_items} binding table
     * (Java API plus datapack channels, see
     * {@link GunItemBindingRegistry}). The datapack channel requires the
     * runtime {@link RegistryAccess} of a loaded world; supply
     * {@code player.level().registryAccess()} or equivalent.</p>
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return {@code true} when the stack is a gun via either channel
     */
    public static boolean isGun(ItemStack stack, RegistryAccess access) {
        return isGun(stack, access, true);
    }

    // ---- Item binding registration (Java API) ----------------------------

    /**
     * Registers an item→gun binding via the Java API (设计规格 物品绑定系统 §6).
     *
     * <p>Thin wrapper over
     * {@link GunItemBindingRegistry#registerBinding(ResourceLocation, GunItemBinding)}:
     * the item id ({@link BuiltInRegistries#ITEM} key of {@code item}) serves
     * as both the binding's item id and its entry key, so no separate key has
     * to be invented. Must be called during mod initialisation (before
     * datapack loading begins). When a datapack JSON later declares a binding
     * for the same item id, the Java-API binding takes priority (注册表服务
     * 语义, 设计文档 §注册冲突与覆盖) and the shadowed datapack entry is
     * reported with a {@code WARN} by the post-reload
     * {@code ItemBindingValidator} (设计规格 物品绑定系统 §3.2).</p>
     *
     * @param item  the bound item; must not be {@code null}
     * @param gunId the target gun entry's registry key, e.g.
     *              {@code mypack:sword_rifle}; must not be {@code null}
     */
    public static void registerGunItem(ItemLike item, ResourceLocation gunId) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(gunId, "gunId");
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item.asItem());
        GunItemBindingRegistry.registerBinding(
                itemId, new GunItemBinding(itemId, gunId));
    }

    /**
     * Registers an item→plugin binding via the Java API (设计规格 物品绑定系统
     * §6).
     *
     * <p>Thin wrapper over
     * {@link PluginItemBindingRegistry#registerBinding(ResourceLocation, PluginItemBinding)}:
     * the item id ({@link BuiltInRegistries#ITEM} key of {@code item}) serves
     * as both the binding's item id and its entry key. Must be called during
     * mod initialisation (before datapack loading begins). When a datapack
     * JSON later declares a binding for the same item id, the Java-API binding
     * takes priority (注册表服务语义, 设计文档 §注册冲突与覆盖) and the
     * shadowed datapack entry is reported with a {@code WARN} by the
     * post-reload {@code ItemBindingValidator} (设计规格 物品绑定系统 §3.2).</p>
     *
     * @param item     the bound item; must not be {@code null}
     * @param pluginId the target plugin entry's registry key, e.g.
     *                 {@code mypack:light_plugin}; must not be {@code null}
     */
    public static void registerPluginItem(ItemLike item, ResourceLocation pluginId) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(pluginId, "pluginId");
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item.asItem());
        PluginItemBindingRegistry.registerBinding(
                itemId, new PluginItemBinding(itemId, pluginId));
    }

    /**
     * Resolves the gun definition id of a gun stack (设计规格 物品绑定系统 §4.1).
     *
     * <p>Dual-channel resolution: the <b>component channel is checked
     * first</b> — a {@code gun_data} component yields its
     * {@link GunData#gunId()}; when no component is present, the binding
     * channel resolves the gun id from the {@code modularshoot:gun_items}
     * binding table (Java API plus datapack). Returns
     * {@link Optional#empty()} when neither channel identifies a gun.</p>
     *
     * @param stack  the stack to inspect; must not be {@code null}
     * @param access the runtime registry view; must not be {@code null}
     * @return the gun definition id, or empty when the stack is not a gun
     */
    public static Optional<ResourceLocation> resolveGunId(
            ItemStack stack, RegistryAccess access) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        GunData gunData = stack.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData != null) {
            return Optional.of(gunData.gunId());
        }
        return findBoundGunId(stack, access, true);
    }

    /**
     * Resolves the gun ItemStack that fired the given bullet snapshot.
     *
     * <p>Delegates to {@link GunResolver#resolveGunFromSnapshot}. This is a
     * pure read-only query used by trait runtime hooks (e.g. {@code onHit})
     * that need to write per-gun state back to the firing gun. The backtrack
     * walks three links: snapshot &rarr; shooter uuid &rarr; player &rarr;
     * inventory stack, null-checking each step so the method never throws
     * (设计文档 §resolveGunFromSnapshot).</p>
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Read {@link BulletSnapshot#getGunInstanceUuid()}; if {@code null}
     *       (independent firing such as turrets/traps) return {@code null}.</li>
     *   <li>Read {@link BulletSnapshot#getShooter()} (player uuid); if
     *       {@code null} return {@code null}.</li>
     *   <li>Look up the player in {@code level}; if {@code level} is null or
     *       the player is offline/not found return {@code null}.</li>
     *   <li>Scan the player's main inventory and offhand for a
     *       {@code modularshoot:gun} stack whose
     *       {@link GunData#gunInstanceUuid()} equals the snapshot's uuid;
     *       return the first match, or {@code null} when not found.</li>
     * </ol>
     *
     * <p>Known limitation: if the player has dropped, stored or cross-dimension
     * moved the gun before the bullet hits, this method returns {@code null}
     * and per-gun state cannot be written. Switching main hand while the gun
     * remains in the inventory is covered by the uuid lookup. Upper-layer mods
     * that must retain counts in this scenario should record a per-player
     * "pending kills" fallback and write it back when the player next holds the
     * gun (设计文档 §已知局限).</p>
     *
     * @param snapshot the bullet snapshot to backtrack from; must not be
     *                 {@code null}
     * @param level    the level to resolve the player in, or {@code null}
     * @return the firing gun ItemStack, or {@code null} when the gun cannot be
     *         located
     */
    @Nullable
    public static ItemStack resolveGunFromSnapshot(BulletSnapshot snapshot, @Nullable Level level) {
        Objects.requireNonNull(snapshot, "snapshot");
        return GunResolver.resolveGunFromSnapshot(snapshot, level);
    }

    // ---- State views ----------------------------------------------------

    /**
     * Returns a {@link GunState} view for the given gun item stack.
     *
     * <p>Delegates to {@link GunState#of}. Returns {@code null} when the stack
     * is not a gun (checked via
     * {@link #isGun(ItemStack, RegistryAccess)} with the player's runtime
     * registry view, so binding-channel guns without an attached
     * {@code gun_data} component are also recognized — their state storage
     * only takes effect through the binding channel). The returned view is a
     * lightweight wrapper over the stack and the supplied
     * {@link RegistryAccess}; it is not cached and may be created freely on
     * every read/write site (设计文档 §读写 API).</p>
     *
     * @param gun    the gun item stack; must not be {@code null}
     * @param player the player context used to resolve the runtime registry;
     *               must not be {@code null}
     * @return a {@link GunState} instance, or {@code null} when the stack is
     *         not a gun
     */
    @Nullable
    public static GunState getState(ItemStack gun, Player player) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(player, "player");
        if (!isGun(gun, player.registryAccess())) {
            return null;
        }
        return GunState.of(gun, player);
    }

    /**
     * Returns a {@link PlayerState} view for the given player.
     *
     * <p>Delegates to {@link PlayerState#of}. The returned view is a
     * lightweight wrapper over the player and the supplied
     * {@link RegistryAccess}; it is not cached and may be created freely on
     * every read/write site (设计文档 §读写 API).</p>
     *
     * @param player the player; must not be {@code null}
     * @return a {@link PlayerState} instance
     */
    public static PlayerState getPlayerState(Player player) {
        Objects.requireNonNull(player, "player");
        return PlayerState.of(player);
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

    /**
     * Registers a per-pellet shoot effect that runs inside the step-seven
     * pellet loop, right after each pellet's snapshot copy and before spread
     * application (机制三 效果贡献者, 规格 §5).
     *
     * <p>Delegates to {@link ShootEffectRegistry#register}. Effects are
     * stackable: every registered effect runs on every pellet in registration
     * order, and later effects see the snapshot mutations of earlier ones.
     * Recommended mutations are {@code setTrait} / {@code multiplyStat} /
     * {@code setStat}; mutating exclusive single-value fields
     * ({@code setDamageType}, visual {@code base}) is forbidden — exclusive
     * effects must go through the variant pool (机制四) per 规格 §5.2. Safe
     * to call during mod common-setup; the framework registers zero effects by
     * default.</p>
     *
     * @param effect the effect to register; must not be {@code null}
     */
    public static void registerShootEffect(ShootEffect effect) {
        Objects.requireNonNull(effect, "effect");
        ShootEffectRegistry.register(effect);
    }

    /**
     * Registers a variant weight contributor that feeds modifiers into the
     * per-shot variant pool (机制四 §6.2 来源 3).
     *
     * <p>Delegates to {@link VariantContributorRegistry#register}. The
     * contributor declares "variant id &rarr; weight modifier" pairs through
     * the {@code VariantContributionSink} it receives; modifiers reuse the
     * vanilla {@code AttributeModifier} record with its {@code Operation}
     * three-stage semantics (规格 §6.3): {@code ADD_VALUE} adds to the base
     * weight, {@code ADD_MULTIPLIED_BASE} multiplies only the base part (a
     * fire-bullet doubling trinket is useless on guns with a zero base weight),
     * and {@code ADD_MULTIPLIED_TOTAL} scales the final weight. A variant id
     * that no gun/plugin declares still enters the pool when the contributor
     * introduces it, using the variant's own {@code base_weight} as its base
     * weight (default {@code 0.0} — a weight-less contribution stays zero and
     * is excluded from the roll); ids already declared by the gun
     * ({@code variants}) or an installed plugin ({@code adds_variants}) keep
     * their declared weight as authoritative. Safe to call during mod
     * common-setup; the framework registers zero contributors by default.</p>
     *
     * @param contributor the contributor to register; must not be {@code null}
     */
    public static void registerVariantContributor(VariantContributor contributor) {
        Objects.requireNonNull(contributor, "contributor");
        VariantContributorRegistry.register(contributor);
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

    // ---- Damage handlers -------------------------------------------------

    /**
     * Registers a global damage post-processor that runs before bullet
     * damage is applied to a hit entity.
     *
     * <p>Delegates to {@link DamageHandlerRegistry#register}. Registered
     * handlers execute in registration order; each handler's return value
     * feeds the next handler's input, and the final value is the damage
     * passed to {@code hurt()}. Safe to call during mod common-setup
     * (设计文档 §伤害后处理).</p>
     *
     * @param handler the damage handler to register; must not be {@code null}
     */
    public static void registerDamageHandler(DamageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        DamageHandlerRegistry.register(handler);
    }

    // ---- Registration coordination --------------------------------------

    /**
     * Marks an entry id as registered by the Java API in the given framework
     * registry.
     *
     * <p>Add-on mods that register entries programmatically (via the Java API
     * rather than datapack JSON) should call this during mod initialisation,
     * before world load. When a datapack JSON later attempts to register the
     * same id, the framework's {@link RegistrationCoordinator} detects the
     * conflict, rejects the datapack override, and logs a {@code WARN}
     * (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>This is a no-op when the id has already been marked; duplicate marks
     * for the same registry + id are safely ignored.</p>
     *
     * @param registryKey the framework registry the id is registered in (one
     *                    of the six {@code modularshoot:*} registries)
     * @param id          the entry id claimed by the Java API
     * @param <T>         the registry value type
     */
    public static <T> void markJavaApiRegistered(
            ResourceKey<Registry<T>> registryKey, ResourceLocation id) {
        Objects.requireNonNull(registryKey, "registryKey");
        Objects.requireNonNull(id, "id");
        RegistrationCoordinator.markJavaApiRegistered(registryKey, id);
    }

    // ---- Dual-channel recognition helpers (private) ----------------------

    /**
     * Shared gun-recognition core for the {@link #isGun(ItemStack)} /
     * {@link #isGun(ItemStack, RegistryAccess)} overloads (设计规格 物品绑定系统
     * §4.1): component channel first ({@code gun_data}), binding channel as
     * fallback. {@code includeDatapack} selects the degraded query
     * (Java-API bindings only, {@link RegistryAccess#EMPTY}) or the full
     * query (Java API + datapack) against the caller's registry view.
     */
    private static boolean isGun(ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        return stack.has(ModularShootDataComponents.GUN_DATA.get())
                || findBoundGunId(stack, access, includeDatapack).isPresent();
    }

    /**
     * Shared plugin-recognition core for the {@link #isPlugin(ItemStack)} /
     * {@link #isPlugin(ItemStack, RegistryAccess)} overloads (设计规格 物品绑定
     * 系统 §4.1): component channel first ({@code plugin_data}), binding
     * channel as fallback. See {@link #isGun(ItemStack, RegistryAccess, boolean)}
     * for the {@code includeDatapack} semantics.
     */
    private static boolean isPlugin(ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(access, "access");
        return stack.has(ModularShootDataComponents.PLUGIN_DATA.get())
                || findBoundPluginId(stack, access, includeDatapack).isPresent();
    }

    /**
     * Resolves the gun id bound to the stack's item id via the
     * {@code modularshoot:gun_items} binding table. Returns
     * {@link Optional#empty()} when the item id cannot be resolved or no
     * binding matches.
     */
    private static Optional<ResourceLocation> findBoundGunId(
            ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        return stack.getItemHolder().unwrapKey()
                .map(ResourceKey::location)
                .flatMap(itemId -> GunItemBindingRegistry.getBoundGunId(
                        includeDatapack ? access : RegistryAccess.EMPTY, itemId));
    }

    /**
     * Resolves the plugin id bound to the stack's item id via the
     * {@code modularshoot:plugin_items} binding table. Returns
     * {@link Optional#empty()} when the item id cannot be resolved or no
     * binding matches.
     */
    private static Optional<ResourceLocation> findBoundPluginId(
            ItemStack stack, RegistryAccess access, boolean includeDatapack) {
        return stack.getItemHolder().unwrapKey()
                .map(ResourceKey::location)
                .flatMap(itemId -> PluginItemBindingRegistry.getBoundPluginId(
                        includeDatapack ? access : RegistryAccess.EMPTY, itemId));
    }
}
