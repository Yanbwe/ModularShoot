package org.yanbwe.modularshoot.plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
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
 */
public final class PluginLockService {

    private PluginLockService() {
    }

    /**
     * Locks or unlocks a specific installed plugin instance on a gun stack.
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
     * @param gun          the gun item stack to modify (mutated on success)
     * @param instanceUuid the instance uuid of the plugin to lock/unlock
     * @param locked       {@code true} to lock, {@code false} to unlock
     */
    public static void setPluginLocked(ItemStack gun, UUID instanceUuid, boolean locked) {
        if (!gun.is(ModularShootItems.GUN_ITEM.get())) {
            return;
        }
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return;
        }
        Optional<PluginInstance> target = findPlugin(gunData.installedPlugins(), instanceUuid);
        if (target.isEmpty()) {
            return;
        }
        if (target.get().locked() == locked) {
            return;
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
