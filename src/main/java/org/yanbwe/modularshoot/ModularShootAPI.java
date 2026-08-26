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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.bullet.BulletRecord;
import org.yanbwe.modularshoot.bullet.BulletSnapshot;
import org.yanbwe.modularshoot.bullet.BulletSnapshotBuilder;
import org.yanbwe.modularshoot.bullet.CreationCoordinator;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginData;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.datapack.RegistrationCoordinator;
import org.yanbwe.modularshoot.plugin.EffectiveSlotService;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginDefinitionProvider;
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
import org.yanbwe.modularshoot.registry.gun.GunDefinitionProvider;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;
import org.yanbwe.modularshoot.registry.shooter.ShooterDefinition;
import org.yanbwe.modularshoot.registry.shooter.ShooterRegistry;
import org.yanbwe.modularshoot.damage.DamageHandler;
import org.yanbwe.modularshoot.damage.DamageHandlerRegistry;
import org.yanbwe.modularshoot.damage.ModularShootDamageTypes;
import org.yanbwe.modularshoot.shooting.ShootEffect;
import org.yanbwe.modularshoot.shooting.ShootEffectRegistry;
import org.yanbwe.modularshoot.shooting.ShootPredicate;
import org.yanbwe.modularshoot.shooting.ShootPredicateRegistry;
import org.yanbwe.modularshoot.state.GunState;
import org.yanbwe.modularshoot.state.PlayerState;
import org.yanbwe.modularshoot.trait.TraitCallbacks;
import org.yanbwe.modularshoot.trait.TraitHookRegistry;
import org.yanbwe.modularshoot.trait.TraitHookType;
import org.yanbwe.modularshoot.util.GunRecognition;
import org.yanbwe.modularshoot.util.GunResolver;
import org.yanbwe.modularshoot.variant.VariantContributor;
import org.yanbwe.modularshoot.variant.VariantContributorRegistry;

/**
 * Unified facade entry point for the ModularShoot framework.
 *
 * <p>This class is the single public API surface that other mods should use to
 * interact with the framework — guns, plugins, bullets, states, traits,
 * damage and shooting hooks alike (审查 O2: 类级注释修正，门面实际覆盖全部子系统). Every
 * method is static and delegates to the dedicated service classes
 * ({@link PluginUninstallService}, {@link PluginLockService},
 * {@link PluginValidationService}, {@link PluginRegistry},
 * {@link PluginTypeRegistry}, {@link GunRegistry} and friends). No business
 * logic lives here; the facade only validates inputs at the boundary and
 * forwards the call to the appropriate service.</p>
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
 * <h2>Extension-point catalogue (审查 O8)</h2>
 * <p>Third-party extension points, grouped by subsystem:</p>
 * <ul>
 *   <li><b>Registration</b> — {@link #registerGun},
 *       {@link #registerGunDefinitionProvider}, {@link #registerPlugin},
 *       {@link #registerPluginDefinitionProvider},
 *       {@link #registerGunItem(ItemLike, ResourceLocation)},
 *       {@link #registerPluginItem(ItemLike, ResourceLocation)}.</li>
 *   <li><b>Shooting pipeline</b> — {@link #registerShootPredicate}
 *       (cancelable pre-shot gate), {@link #registerShootEffect}
 *       (per-pellet snapshot mutation); the {@code PreShootEvent} /
 *       {@code PostShootEvent} game-bus events for observation/cancellation.</li>
 *   <li><b>Plugin lifecycle</b> — {@link #registerPluginValidator} plus the
 *       {@code Pre/PostPluginInstallEvent} and {@code Pre/PostPluginUninstallEvent}
 *       game-bus events (pre-events are cancelable; the install pre-event
 *       exposes the selected slot type and a custom cancel reason).</li>
 *   <li><b>Trait runtime hooks</b> — {@link #registerTraitHook} (onTick /
 *       onHit / onBlockHit / onExpire / onRemove; dispatched only for
 *       bullets carrying the trait).</li>
 *   <li><b>Bullets &amp; variants</b> — {@link #fireBullet},
 *       {@link #registerVariantContributor},
 *       {@link org.yanbwe.modularshoot.network.BulletSyncExtraRegistry#register}
 *       (per-bullet sync extension bytes), {@code ClientBulletHitEvent}.</li>
 *   <li><b>Damage</b> — {@link #registerDamageHandler}.</li>
 *   <li><b>Visuals</b> —
 *       {@link org.yanbwe.modularshoot.client.render.DynamicOutlineTintRegistry#register}
 *       (dynamic gun-outline tinting).</li>
 * </ul>
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
        return GunRecognition.isPlugin(stack);
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
        return GunRecognition.isPlugin(stack, access);
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
        return GunRecognition.resolvePluginId(stack, access);
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
     * {@code modularshoot:plugins} and {@code modularshoot:guns} registries
     * are datapack-driven (empty on the main menu); supply
     * {@code player.level().registryAccess()} or equivalent.</p>
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
     * or processed by automation), call
     * {@link PluginUninstallService#uninstallPlugin} directly with an explicit
     * {@link RegistryAccess} instead.</p>
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
     * Removes one randomly chosen uninstallable plugin from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallRandomPlugin}. See that method for
     * the candidate selection and outcome semantics.</p>
     *
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, call
     * {@link PluginUninstallService#uninstallRandomPlugin} directly with an
     * explicit {@link RegistryAccess} instead.</p>
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
     * Removes every plugin whose {@code installedTypeId} matches the given
     * category id from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallPluginsByType}. See that method
     * for the snapshot and per-uuid removal semantics.</p>
     *
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, call
     * {@link PluginUninstallService#uninstallPluginsByType} directly with an
     * explicit {@link RegistryAccess} instead.</p>
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
     * Removes every installed plugin from a gun stack.
     *
     * <p>Delegates to
     * {@link PluginUninstallService#uninstallAllPlugins}. See that method for
     * the snapshot and per-uuid removal semantics.</p>
     *
     * <p>The runtime {@link RegistryAccess} is derived internally from
     * {@code player.level().registryAccess()} (设计文档 §API 签名 — 无
     * RegistryAccess 参数). When the player context may be {@code null}, call
     * {@link PluginUninstallService#uninstallAllPlugins} directly with an
     * explicit {@link RegistryAccess} instead.</p>
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
        // 无提示入口：委托带 hint 的重载（hint 为空），行为与旧版完全一致。
        return installPlugin(gun, pluginStack, player, null);
    }

    /**
     * Programmatically installs a plugin, optionally preferring a specific
     * plugin category (e.g. the category area a UI dragged the plugin onto).
     *
     * <p>Identical to
     * {@link #installPlugin(ItemStack, ItemStack, Player)} except that
     * {@code preferredTypeId} hints the target category: when the hinted
     * category matches the plugin by tag intersection and has a free slot, it
     * is selected directly; otherwise the automatic category selection runs
     * unchanged. The hint is a preference, not a mandate — it can never bypass
     * any validation gate, and an untrusted hint at worst installs into
     * another legitimate category (设计草案 插件安装优先种类-方案 §三).</p>
     *
     * <p>Delegates to
     * {@link PluginInstallService#installPlugin(ItemStack, ItemStack, Player, ResourceLocation, RegistryAccess)};
     * the player must be non-null (installation needs the random source and
     * the runtime {@link RegistryAccess}, both derived from
     * {@code player.level()}). The result carries the modified gun copy and
     * the consumed plugin copy; the caller is responsible for writing them
     * back to the container/inventory (following the container-right-click
     * install path's {@code slot.set} pattern). The input stacks are never
     * mutated (设计文档 §系统四 安装交互 — Apotheosis 不可变风格).</p>
     *
     * @param gun             the target gun item stack (not modified)
     * @param pluginStack     the plugin item stack carrying {@code PluginData}
     *                        (not modified)
     * @param player          the player performing the installation; must not
     *                        be {@code null}
     * @param preferredTypeId the plugin category id to prefer, or {@code null}
     *                        for pure auto-selection; honoured only when the
     *                        hinted category is a valid candidate
     * @return an {@link PluginInstallService.InstallResult}; on success it
     *         carries the modified copies
     * @throws NullPointerException when any non-null parameter is {@code null}
     *                              (consistent with the other facade methods)
     */
    public static PluginInstallService.InstallResult installPlugin(
            ItemStack gun, ItemStack pluginStack, Player player,
            @Nullable ResourceLocation preferredTypeId) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(pluginStack, "pluginStack");
        Objects.requireNonNull(player, "player");
        RegistryAccess registryAccess = player.level().registryAccess();
        return PluginInstallService.installPlugin(
                gun, pluginStack, player, preferredTypeId, registryAccess);
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
     * Registers a dynamic gun definition provider.
     *
     * <p>Delegates to {@link GunRegistry#registerGunDefinitionProvider}.
     * The provider is consulted by {@link GunRegistry#getGun} after the
     * Java API map and before the datapack registry, enabling definitions
     * that are per-player or generated at query time (改进① 动态定义来源,
     * 2026-08-10 探讨整理). Registration is process-wide and survives
     * {@code /reload} (same semantics as {@link #registerGun}). Safe to
     * call during mod common-setup.</p>
     *
     * @param provider the provider to register; must not be {@code null}
     */
    public static void registerGunDefinitionProvider(GunDefinitionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        GunRegistry.registerGunDefinitionProvider(provider);
    }

    /**
     * Registers a plugin definition via the Java API (审查 E1 — 与枪械侧
     * {@link #registerGun} 对称的插件写入通道).
     *
     * <p>Delegates to {@link PluginRegistry#registerPlugin}. Must be called
     * during mod initialisation (before datapack loading begins). The
     * registered id is marked with the framework's registration coordinator
     * so that any later datapack JSON attempting to register the same id is
     * rejected with a {@code WARN}. Java-API-registered entries survive
     * {@code /reload} and take priority over datapack entries with the same
     * id.</p>
     *
     * @param pluginId   the plugin definition id, e.g.
     *                   {@code mypack:rapid_affix}; must not be {@code null}
     * @param definition the plugin definition; must not be {@code null}
     */
    public static void registerPlugin(ResourceLocation pluginId, PluginDefinition definition) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(definition, "definition");
        PluginRegistry.registerPlugin(pluginId, definition);
    }

    /**
     * Registers a dynamic plugin definition provider (审查 E1 — 与枪械侧
     * {@link #registerGunDefinitionProvider} 对称).
     *
     * <p>Delegates to {@link PluginRegistry#registerPluginDefinitionProvider}.
     * The provider is consulted by {@link PluginRegistry#getPlugin} after the
     * Java API map and before the datapack registry, enabling definitions
     * computed at query time (programmatic loot affixes etc.). Registration
     * is process-wide and survives {@code /reload}.</p>
     *
     * @param provider the provider to register; must not be {@code null}
     */
    public static void registerPluginDefinitionProvider(PluginDefinitionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        PluginRegistry.registerPluginDefinitionProvider(provider);
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
     * Returns the effective slot configuration of a gun: every slot type the
     * gun can currently hold, mapped to its capacity.
     *
     * <p>The effective key set is the union of the gun definition's base
     * {@code slots} and every installed plugin's {@code adds_slots} keys; a
     * type's capacity is the gun's base value plus the sum of all installed
     * plugins' contributions (negative values occupy slots, matching the
     * {@code adds_slots} semantics). This is the same aggregation the install
     * matching and uninstall overflow checks use, exposed so UI code (e.g.
     * the plugin panel) can render per-category capacity and fill state
     * without re-implementing the aggregation (设计草案 插件安装优先种类-方案
     * §四).</p>
     *
     * <p>Delegates to {@link EffectiveSlotService#effectiveSlots}. Returns an
     * empty map when the stack carries no {@code gun_data} or the gun
     * definition is missing.</p>
     *
     * @param gun    the item stack to inspect; must not be {@code null}
     * @param access the runtime registry view (from a loaded world); must not
     *               be {@code null}
     * @return an immutable map of slot type → effective capacity; empty when
     *         the stack is not a resolvable gun
     */
    public static Map<ResourceLocation, Integer> getEffectiveSlots(
            ItemStack gun, RegistryAccess access) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(access, "access");
        return EffectiveSlotService.effectiveSlots(gun, access);
    }

    /**
     * Looks up a gun definition by id in the {@code modularshoot:guns}
     * registry.
     *
     * <p>Delegates to {@link GunRegistry#getGun}. The registry is
     * datapack-driven and empty on the main menu, so a {@link RegistryAccess}
     * from a loaded world is required. Dynamic providers registered via
     * {@link #registerGunDefinitionProvider} participate in the query
     * (consulted between the Java API map and the datapack registry); see
     * {@link GunRegistry#getGun} for the full source ordering.</p>
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

    /**
     * Looks up a shooter definition by id in the
     * {@code modularshoot:shooters} registry.
     *
     * <p>Delegates to {@link ShooterRegistry#getShooter}. The registry is
     * datapack-driven and empty on the main menu, so a {@link RegistryAccess}
     * from a loaded world is required.</p>
     *
     * @param registryAccess the runtime registry view; must not be {@code null}
     * @param shooterId      the shooter definition id; must not be
     *                       {@code null}
     * @return the matching {@link ShooterDefinition}, or empty when the
     *         registry is absent or the id is not registered
     */
    public static Optional<ShooterDefinition> getShooterDefinition(
            RegistryAccess registryAccess, ResourceLocation shooterId) {
        Objects.requireNonNull(registryAccess, "registryAccess");
        Objects.requireNonNull(shooterId, "shooterId");
        return ShooterRegistry.getShooter(registryAccess, shooterId);
    }

    /**
     * Registers a shooter definition via the Java API.
     *
     * <p>Delegates to {@link ShooterRegistry#registerShooter}. Must be called
     * during mod initialisation (before datapack loading begins, i.e. in the
     * mod constructor or {@code FMLCommonSetupEvent}). The registered id is
     * marked with {@link RegistrationCoordinator#markJavaApiRegistered} so
     * that any later datapack JSON attempting to register the same id is
     * rejected with a {@code WARN} (设计文档 §注册冲突与覆盖, line 2289).</p>
     *
     * <p>Java-API-registered entries survive {@code /reload} and take
     * priority over datapack entries with the same id. See
     * {@link ShooterRegistry#registerShooter} for full semantics.</p>
     *
     * @param shooterId  the shooter definition id, e.g.
     *                   {@code modularshoot:bone_shooter}; must not be
     *                   {@code null}
     * @param definition the shooter definition; must not be {@code null}
     */
    public static void registerShooter(ResourceLocation shooterId, ShooterDefinition definition) {
        Objects.requireNonNull(shooterId, "shooterId");
        Objects.requireNonNull(definition, "definition");
        ShooterRegistry.registerShooter(shooterId, definition);
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
     * @return the gun definition id, or empty when the stack has no
     *         component (审查 O2: 与 getPluginId 等查询统一为 Optional 风格)
     */
    public static Optional<ResourceLocation> getGunId(ItemStack gun) {
        Objects.requireNonNull(gun, "gun");
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        return gunData == null ? Optional.empty() : Optional.of(gunData.gunId());
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
        return GunRecognition.isGun(stack);
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
        return GunRecognition.isGun(stack, access);
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
        return GunRecognition.resolveGunId(stack, access);
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
     * @return a {@link GunState} instance, or empty when the stack is
     *         not a gun (审查 O2: 与其余查询统一为 Optional 风格)
     */
    public static Optional<GunState> getState(ItemStack gun, Player player) {
        Objects.requireNonNull(gun, "gun");
        Objects.requireNonNull(player, "player");
        if (!GunRecognition.isGun(gun, player.registryAccess())) {
            return Optional.empty();
        }
        return Optional.of(GunState.of(gun, player));
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

    // ---- Independent firing ---------------------------------------------

    /**
     * Creates a fresh {@link BulletSnapshotBuilder} for the independent-firing
     * flow (设计文档 §独立发射).
     *
     * <p>Independent firing is the non-player-source path (turret, trap, boss
     * attack, scripted scenario, ...): the bullet's {@link BulletSnapshot} is
     * built by hand rather than frozen by the player shooting engine, then
     * handed to {@link #fireBullet}. Per the independent-firing snapshot
     * convention (设计文档 §独立发射的快照字段约定) the builder always
     * produces snapshots with {@code gunId = null} and
     * {@code gunInstanceUuid = null}; the shooter uuid is supplied separately
     * at {@link #fireBullet} time (null for ownerless sources).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * BulletSnapshot snapshot = ModularShootAPI.createBulletSnapshot()
     *         .stat(modularshoot:hit_damage, 10.0)
     *         .trait(modularshoot:ignite, true)
     *         .style(new BulletStyle(Optional.of(base), List.of()))
     *         .build(level.registryAccess());
     * ModularShootAPI.fireBullet(level, pos, dir, snapshot, null);
     * }</pre>
     *
     * @return a new, reusable snapshot builder
     */
    public static BulletSnapshotBuilder createBulletSnapshot() {
        return new BulletSnapshotBuilder();
    }

    /**
     * Fires a bullet from a custom, non-player source without going through
     * the player shooting engine (设计文档 §独立发射).
     *
     * <p>Delegates to
     * {@link org.yanbwe.modularshoot.bullet.CreationCoordinator#fireBullet}.
     * Unlike the player shoot path this performs <b>no fire-rate control, no
     * ShootPredicate check, no PreShootEvent/PostShootEvent and no sound
     * playback</b> — the bullet enters the normal tick loop (flight,
     * collision, damage, trait hooks) once registered.</p>
     *
     * <p><b>Snapshot conventions</b> (设计文档 §独立发射的快照字段约定): the
     * snapshot should carry {@code gunId = null} and
     * {@code gunInstanceUuid = null} (the
     * {@link #createBulletSnapshot() builder} enforces this). {@code shooter}
     * {@code null} marks an ownerless source (traps, scripts); supply a player
     * uuid to attribute the bullet. The caller is responsible for
     * normalizing {@code position}/{@code direction} expectations — the
     * direction is consumed as-is by the flight simulation.</p>
     *
     * <p><b>Damage-type patch:</b> when {@code snapshot} carries a
     * {@code null} damage type, the framework default
     * ({@link ModularShootDamageTypes#holderOrThrow}) is resolved from
     * {@code level.registryAccess()} and written into the snapshot before
     * firing, so callers may omit the holder entirely
     * (throws {@link IllegalStateException} if the framework damage type is
     * missing from the runtime registries).</p>
     *
     * @param level     the dimension to fire into; must not be {@code null}
     * @param position  the launch position; must not be {@code null}
     * @param direction the initial flight direction (normalized); must not
     *                  be {@code null}
     * @param snapshot  the bullet snapshot (builder- or hand-constructed);
     *                  must not be {@code null}
     * @param shooter   the shooter uuid, or {@code null} for ownerless
     *                  sources
     * @return the newly created and registered {@link BulletRecord}
     * @throws NullPointerException when {@code level}, {@code position},
     *                              {@code direction} or {@code snapshot} is
     *                              {@code null}
     */
    public static BulletRecord fireBullet(
            Level level,
            Vec3 position,
            Vec3 direction,
            BulletSnapshot snapshot,
            @Nullable UUID shooter) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.getDamageType() == null) {
            snapshot.setDamageType(ModularShootDamageTypes.holderOrThrow(level.registryAccess()));
        }
        // 独立发弹路径：gunData 由 BulletFactory 从 snapshot 反查（无则降级），
        // 网络标记由 CreationCoordinator 负责（D-03 短寿命子弹保证）。
        return CreationCoordinator.INSTANCE.fireBullet(
                level, position, direction, snapshot, shooter, null);
    }
}
