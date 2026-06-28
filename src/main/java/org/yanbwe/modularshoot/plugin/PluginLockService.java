package org.yanbwe.modularshoot.plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.item.ModularShootItems;

/**
 * Runtime lock API for installed plugins on a gun stack.
 *
 * <p>Other mods use this service to lock or unlock a specific installed plugin
 * instance by its {@code instanceUuid}, and to query the current lock state.
 * A locked plugin is skipped by non-forced uninstall operations (设计文档
 * §插件锁定 API).</p>
 *
 * <p>All operations locate the target plugin by its stable instance uuid
 * rather than by list index. The installed-plugins list is ordered by install
 * sequence and its indices shift on every install/uninstall, so index-based
 * addressing would be fragile; the instance uuid is stable for the lifetime
 * of the instance and is the canonical identifier for per-instance operations
 * (uninstall, lock, modifier id).</p>
 *
 * <p>The service follows the immutable-data-component pattern: it never mutates
 * a {@link PluginInstance} or {@link GunData} in place. Instead it builds a new
 * plugin list with the targeted instance replaced via
 * {@link PluginInstance#withLocked(boolean)}, wraps it in a new {@link GunData}
 * (with {@code modifierVersion} bumped because a lock change counts as a
 * modification per the {@link GunData} contract) and writes the new component
 * back onto the stack.</p>
 *
 * <p>The {@link #setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)}
 * overload additionally calls
 * {@link AttributeModifierService#refreshModifiers} after the update, honouring
 * the design's refresh-trigger-point list (设计文档 §组件刷新时机). The
 * deprecated {@link #setPluginLocked(ItemStack, UUID, boolean)} overload skips
 * the refresh because a lock-state change does not alter which modifiers are
 * active; callers without a {@link RegistryAccess} may use it safely.</p>
 */
public final class PluginLockService {

    private PluginLockService() {
    }

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack,
     * then refreshes the {@code ATTRIBUTE_MODIFIERS} component so the stack
     * stays consistent with the design's trigger-point list (设计文档
     * §组件刷新时机).
     *
     * <p>This is the preferred overload: it follows the same pattern as
     * {@link org.yanbwe.modularshoot.plugin.PluginInstallService} and
     * {@link org.yanbwe.modularshoot.plugin.PluginUninstallService}, which
     * both call {@link AttributeModifierService#refreshModifiers} after
     * mutating the plugin list. Although a lock-state change does not alter
     * which modifiers are active (a locked plugin still contributes its
     * modifiers &mdash; lock only prevents non-forced uninstall), the design
     * explicitly lists lock changes as a refresh trigger point, so this
     * overload honours that contract.</p>
     *
     * <p>This is a no-op when any of the following holds:
     * <ul>
     *   <li>the stack is not a {@code modularshoot:gun} item;</li>
     *   <li>the stack carries no {@code gun_data} component;</li>
     *   <li>no installed plugin matches the given {@code instanceUuid};</li>
     *   <li>the matching plugin is already in the requested lock state
     *       (avoids a spurious {@code modifierVersion} bump that would break
     *       anti-cheat stale-modifier detection).</li>
     * </ul>
     *
     * @param gun            the gun item stack to modify (mutated on success)
     * @param instanceUuid   the instance uuid of the plugin to lock/unlock
     * @param locked         {@code true} to lock, {@code false} to unlock
     * @param registryAccess the runtime registry view used to refresh
     *                       attribute modifiers after the update
     */
    public static void setPluginLocked(
            ItemStack gun, UUID instanceUuid, boolean locked, RegistryAccess registryAccess) {
        if (!applyLockState(gun, instanceUuid, locked)) {
            return;
        }
        AttributeModifierService.refreshModifiers(gun, registryAccess);
    }

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack
     * <em>without</em> refreshing the {@code ATTRIBUTE_MODIFIERS} component.
     *
     * <p><strong>This overload does not refresh attribute modifiers.</strong>
     * It is retained for backward compatibility with callers that do not have
     * a {@link RegistryAccess} available. Because a lock-state change does not
     * alter which modifiers are active (a locked plugin still contributes its
     * modifiers &mdash; lock only prevents non-forced uninstall), skipping the
     * refresh is safe in practice: the modifier set on the stack remains
     * correct. The {@code modifierVersion} bump is still applied so that
     * anti-cheat stale-modifier detection stays consistent.</p>
     *
     * <p>Callers that have a {@link RegistryAccess} should prefer
     * {@link #setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)} to
     * fully honour the design's refresh-trigger-point list (设计文档
     * §组件刷新时机).</p>
     *
     * @param gun          the gun item stack to modify (mutated on success)
     * @param instanceUuid the instance uuid of the plugin to lock/unlock
     * @param locked       {@code true} to lock, {@code false} to unlock
     * @deprecated Use {@link #setPluginLocked(ItemStack, UUID, boolean, RegistryAccess)}
     *             to ensure the {@code ATTRIBUTE_MODIFIERS} component is
     *             refreshed after the lock change, per the design's
     *             trigger-point list.
     */
    @Deprecated
    public static void setPluginLocked(ItemStack gun, UUID instanceUuid, boolean locked) {
        applyLockState(gun, instanceUuid, locked);
    }

    /**
     * Applies the lock-state change to the target plugin instance on the gun
     * stack, writing the updated {@link GunData} component and bumping
     * {@code modifierVersion}.
     *
     * <p>Extracted from the public {@code setPluginLocked} overloads so both
     * can share the same validation and mutation logic. The caller decides
     * whether to refresh attribute modifiers afterwards.</p>
     *
     * @param gun          the gun item stack to modify (mutated on success)
     * @param instanceUuid the instance uuid of the plugin to lock/unlock
     * @param locked       {@code true} to lock, {@code false} to unlock
     * @return {@code true} if the lock state was changed and the component
     *         was written; {@code false} if any guard condition failed (not a
     *         gun, no gun data, plugin absent, or already in the requested
     *         state)
     */
    private static boolean applyLockState(ItemStack gun, UUID instanceUuid, boolean locked) {
        if (!gun.is(ModularShootItems.GUN_ITEM.get())) {
            return false;
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return false;
        }
        Optional<PluginInstance> target = findPlugin(gunData.installedPlugins(), instanceUuid);
        if (target.isEmpty()) {
            return false;
        }
        if (target.get().locked() == locked) {
            return false;
        }
        List<PluginInstance> newPlugins = gunData.installedPlugins().stream()
                .map(p -> p.instanceUuid().equals(instanceUuid) ? p.withLocked(locked) : p)
                .toList();
        GunData newGunData = new GunData(
                gunData.gunId(),
                gunData.gunInstanceUuid(),
                newPlugins,
                gunData.modifierVersion() + 1,
                gunData.state());
        gun.set(ModularShootDataComponents.GUN_DATA.get(), newGunData);
        return true;
    }

    /**
     * Queries whether a specific installed plugin instance is locked.
     *
     * @param gun          the gun item stack to inspect
     * @param instanceUuid the instance uuid of the plugin to query
     * @return {@code true} if the plugin exists and is locked; {@code false}
     *         if the stack is not a gun, carries no gun data, the plugin is
     *         absent, or the plugin is unlocked
     */
    public static boolean isPluginLocked(ItemStack gun, UUID instanceUuid) {
        if (!gun.is(ModularShootItems.GUN_ITEM.get())) {
            return false;
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return false;
        }
        return findPlugin(gunData.installedPlugins(), instanceUuid)
                .map(PluginInstance::locked)
                .orElse(false);
    }

    /**
     * Locates an installed plugin by its instance uuid.
     *
     * @param plugins      the installed plugin list to search
     * @param instanceUuid the instance uuid to match
     * @return the matching {@link PluginInstance}, or empty if none matches
     */
    private static Optional<PluginInstance> findPlugin(List<PluginInstance> plugins, UUID instanceUuid) {
        return plugins.stream()
                .filter(p -> p.instanceUuid().equals(instanceUuid))
                .findFirst();
    }
}
