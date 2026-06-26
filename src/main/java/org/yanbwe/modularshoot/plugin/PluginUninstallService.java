package org.yanbwe.modularshoot.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularshoot.attribute.AttributeModifierService;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.degradation.PluginDegradationHandler;
import org.yanbwe.modularshoot.item.ModularShootItems;
import org.yanbwe.modularshoot.plugin.event.PostPluginUninstallEvent;
import org.yanbwe.modularshoot.plugin.event.PrePluginUninstallEvent;

/**
 * Uninstall API for removing plugins from a gun stack.
 *
 * <p>Provides four uninstall strategies plus a query method:</p>
 * <ul>
 *   <li>{@link #uninstallPlugin} &mdash; remove a single plugin identified by
 *       its stable instance uuid;</li>
 *   <li>{@link #uninstallRandomPlugin} &mdash; remove one randomly chosen
 *       uninstallable plugin;</li>
 *   <li>{@link #uninstallPluginsByType} &mdash; remove every plugin whose
 *       {@code installedTypeId} matches a given category id;</li>
 *   <li>{@link #uninstallAllPlugins} &mdash; remove every installed plugin;</li>
 *   <li>{@link #getInstalledPlugins} &mdash; return the current installed
 *       plugin list (read-only).</li>
 * </ul>
 *
 * <h2>Parameter semantics</h2>
 * <ul>
 *   <li><b>{@code player}</b> &mdash; may be {@code null} (e.g. when the gun
 *       is in a chest or processed by automation). When {@code returnItems}
 *       is {@code true} <em>and</em> {@code player} is non-null, the removed
 *       plugin is returned to the player's inventory; if the inventory is
 *       full the stack is dropped at the player's position. When
 *       {@code returnItems} is {@code false} or {@code player} is
 *       {@code null}, the plugin is silently discarded.</li>
 *   <li><b>{@code force}</b> &mdash; when {@code true}, locked plugins are
 *       removed regardless of their {@code locked} flag. When {@code false},
 *       locked plugins are skipped (not removed) and reported as
 *       {@code success = false}.</li>
 *   <li><b>{@code returnItems}</b> &mdash; controls whether the removed
 *       plugin is returned as an item stack. See the {@code player}
 *       description above for the full interaction. A plugin whose
 *       definition is missing from the {@code modularshoot:plugins} registry
 *       (degraded) is <b>never</b> returned even when {@code returnItems} is
 *       {@code true}; it is silently destroyed instead, since there is no
 *       definition to bind the returned stack to (设计文档 §插件 pluginId 失效降级).</li>
 * </ul>
 *
 * <h2>Instance-uuid locating</h2>
 * <p>All per-plugin operations locate the target by its
 * {@link PluginInstance#instanceUuid()}, never by list index. The
 * {@code installedPlugins} list is ordered by install sequence and its
 * indices shift on every install/uninstall, so an index cached across calls
 * would silently drift. The instance uuid is generated once at install time
 * and is stable for the lifetime of the instance, making it safe to reference
 * across calls. Batch uninstall methods snapshot the uuid list before
 * iterating and then call {@link #uninstallPlugin} per uuid, so each removal
 * re-resolves by uuid and is immune to index drift.</p>
 *
 * <h2>Event order</h2>
 * <p>For each plugin removal the framework fires, in order:</p>
 * <ol>
 *   <li>{@link PrePluginUninstallEvent} &mdash; cancelable; if cancelled the
 *       plugin is <strong>not</strong> removed and no post event fires;</li>
 *   <li>the plugin is removed from the {@code gun_data} component and
 *       {@link AttributeModifierService#refreshModifiers} is called;</li>
 *   <li>{@link PostPluginUninstallEvent} &mdash; not cancelable.</li>
 * </ol>
 * <p>In batch operations a cancelled plugin is skipped and the framework
 * continues with the remaining plugins; cancelling one does not abort the
 * batch.</p>
 *
 * <p>The service follows the immutable-data-component pattern: it never
 * mutates a {@link PluginInstance} or {@link GunData} in place. Each removal
 * builds a new plugin list, wraps it in a new {@link GunData} (with
 * {@code modifierVersion} bumped) and writes the new component back onto the
 * stack.</p>
 */
public final class PluginUninstallService {

    private PluginUninstallService() {
    }

    /**
     * Removes a single plugin identified by its instance uuid from a gun
     * stack.
     *
     * <p>Validation and short-circuit order:</p>
     * <ol>
     *   <li>the stack must be a {@code modularshoot:gun} item with a
     *       {@code gun_data} component, otherwise
     *       {@code (false, null, instanceUuid)};</li>
     *   <li>the plugin must be present in the installed list, otherwise
     *       {@code (false, null, instanceUuid)};</li>
     *   <li>if {@code force} is {@code false} and the plugin is locked,
     *       skip and return {@code (false, pluginId, instanceUuid)};</li>
     *   <li>fire {@link PrePluginUninstallEvent}; if cancelled, skip and
     *       return {@code (false, pluginId, instanceUuid)};</li>
     *   <li>remove the plugin, write the new {@link GunData}, optionally
     *       return the item (skipped when the plugin definition is missing
     *       &mdash; degraded plugins are destroyed, not returned, even with
     *       {@code returnItems = true}), refresh modifiers, fire
     *       {@link PostPluginUninstallEvent}, and return
     *       {@code (true, pluginId, instanceUuid)}.</li>
     * </ol>
     *
     * @param gun            the gun item stack to modify (mutated on success)
     * @param instanceUuid   the instance uuid of the plugin to remove
     * @param player         the player context for item return, or
     *                       {@code null} when triggered by a non-player source
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return the removed plugin as an
     *                       item stack to {@code player} (when non-null and
     *                       the plugin definition still exists; degraded
     *                       plugins are destroyed instead)
     * @param registryAccess the runtime registry view (for modifier refresh
     *                       and degradation check)
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
        GunData gunData = readGunData(gun);
        if (gunData == null) {
            return new UninstallResult(false, null, instanceUuid);
        }
        Optional<PluginInstance> target = findPlugin(gunData.installedPlugins(), instanceUuid);
        if (target.isEmpty()) {
            return new UninstallResult(false, null, instanceUuid);
        }
        PluginInstance plugin = target.get();
        if (!force && plugin.locked()) {
            return new UninstallResult(false, plugin.pluginId(), instanceUuid);
        }
        PrePluginUninstallEvent preEvent = new PrePluginUninstallEvent(
                player, gun, instanceUuid, plugin.pluginId());
        NeoForge.EVENT_BUS.post(preEvent);
        if (preEvent.isCanceled()) {
            return new UninstallResult(false, plugin.pluginId(), instanceUuid);
        }
        removePluginFromGun(gun, gunData, instanceUuid);
        if (returnItems && player != null
                && !PluginDegradationHandler.isPluginDefinitionMissing(plugin, registryAccess)) {
            returnPluginItem(player, plugin.pluginId());
        }
        AttributeModifierService.refreshModifiers(gun, registryAccess);
        NeoForge.EVENT_BUS.post(new PostPluginUninstallEvent(
                player, gun, plugin.pluginId(), instanceUuid));
        return new UninstallResult(true, plugin.pluginId(), instanceUuid);
    }

    /**
     * Removes one randomly chosen uninstallable plugin from a gun stack.
     *
     * <p>Candidates are the installed plugins that are either unlocked or
     * would be force-removed. One is picked uniformly at random and
     * delegated to {@link #uninstallPlugin}. When no candidate is available
     * (empty list or all locked without {@code force}) the result is
     * {@code (false, null, null)}.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success)
     * @param player         the player context for item return, or
     *                       {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return the removed plugin item
     * @param registryAccess the runtime registry view
     * @return an {@link UninstallResult} describing the outcome
     */
    public static UninstallResult uninstallRandomPlugin(
            ItemStack gun,
            @Nullable Player player,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess
    ) {
        GunData gunData = readGunData(gun);
        if (gunData == null) {
            return new UninstallResult(false, null, null);
        }
        List<PluginInstance> candidates = gunData.installedPlugins().stream()
                .filter(p -> force || !p.locked())
                .toList();
        if (candidates.isEmpty()) {
            return new UninstallResult(false, null, null);
        }
        PluginInstance selected = candidates.get(
                ThreadLocalRandom.current().nextInt(candidates.size()));
        return uninstallPlugin(gun, selected.instanceUuid(), player, force, returnItems, registryAccess);
    }

    /**
     * Removes every plugin whose {@code installedTypeId} matches the given
     * category id from a gun stack.
     *
     * <p>The matching uuids are snapshotted before any removal, then each is
     * uninstalled individually via {@link #uninstallPlugin} so that the
     * shrinking list does not cause index drift. Plugins whose pre-event is
     * cancelled are skipped; the remaining plugins are still processed.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success)
     * @param player         the player context for item return, or
     *                       {@code null}
     * @param pluginTypeId   the category id to match against
     *                       {@link PluginInstance#installedTypeId()}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return each removed plugin item
     * @param registryAccess the runtime registry view
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
        GunData gunData = readGunData(gun);
        if (gunData == null) {
            return List.of();
        }
        List<UUID> uuids = gunData.installedPlugins().stream()
                .filter(p -> p.installedTypeId().equals(pluginTypeId))
                .map(PluginInstance::instanceUuid)
                .toList();
        return uninstallByUuids(gun, player, force, returnItems, registryAccess, uuids);
    }

    /**
     * Removes every installed plugin from a gun stack.
     *
     * <p>All instance uuids are snapshotted before any removal, then each is
     * uninstalled individually via {@link #uninstallPlugin} so that the
     * shrinking list does not cause index drift. Plugins whose pre-event is
     * cancelled are skipped; the remaining plugins are still processed.</p>
     *
     * @param gun            the gun item stack to modify (mutated on success)
     * @param player         the player context for item return, or
     *                       {@code null}
     * @param force          {@code true} to ignore the {@code locked} flag
     * @param returnItems    {@code true} to return each removed plugin item
     * @param registryAccess the runtime registry view
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
        GunData gunData = readGunData(gun);
        if (gunData == null) {
            return List.of();
        }
        List<UUID> uuids = gunData.installedPlugins().stream()
                .map(PluginInstance::instanceUuid)
                .toList();
        return uninstallByUuids(gun, player, force, returnItems, registryAccess, uuids);
    }

    /**
     * Returns the installed plugin list of a gun stack.
     *
     * <p>This is a read-only query; it does not modify the stack and does not
     * require a {@link RegistryAccess}. The returned list is the immutable
     * list stored in the {@code gun_data} component (or an empty list when
     * the stack is invalid).</p>
     *
     * @param gun the gun item stack to inspect
     * @return the immutable installed plugin list, or an empty list when the
     *         stack is not a gun or carries no {@code gun_data} component
     */
    public static List<PluginInstance> getInstalledPlugins(ItemStack gun) {
        GunData gunData = readGunData(gun);
        if (gunData == null) {
            return List.of();
        }
        return gunData.installedPlugins();
    }

    // ---- helpers -------------------------------------------------------

    /**
     * Reads and validates the {@link GunData} component from a stack.
     *
     * @param gun the stack to inspect
     * @return the {@link GunData}, or {@code null} when the stack is not a
     *         gun item or carries no {@code gun_data} component
     */
    @Nullable
    private static GunData readGunData(ItemStack gun) {
        if (!gun.is(ModularShootItems.GUN_ITEM.get())) {
            return null;
        }
        return gun.get(ModularShootDataComponents.GUN_DATA.get());
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

    /**
     * Removes the plugin with the given uuid from the gun's data component,
     * building a new {@link GunData} with {@code modifierVersion} bumped and
     * writing it back onto the stack. Does <strong>not</strong> refresh the
     * {@code ATTRIBUTE_MODIFIERS} component; the caller is responsible for
     * invoking {@link AttributeModifierService#refreshModifiers} afterwards.
     *
     * @param gun          the gun stack to update (mutated)
     * @param gunData      the current gun data
     * @param instanceUuid the uuid of the plugin to remove
     */
    private static void removePluginFromGun(
            ItemStack gun, GunData gunData, UUID instanceUuid) {
        List<PluginInstance> newPlugins = gunData.installedPlugins().stream()
                .filter(p -> !p.instanceUuid().equals(instanceUuid))
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
     * Creates a plugin item stack for the given plugin id and gives it to the
     * player. When the player's inventory is full the stack is dropped at the
     * player's position instead.
     *
     * @param player   the player to receive the plugin item
     * @param pluginId the plugin definition id to create a stack for
     */
    private static void returnPluginItem(Player player, ResourceLocation pluginId) {
        ItemStack pluginStack = PluginRegistry.createPluginStack(pluginId);
        if (!player.getInventory().add(pluginStack)) {
            player.drop(pluginStack, false);
        }
    }

    /**
     * Uninstalls each plugin in a uuid list individually, collecting the
     * results. Each uuid is resolved fresh inside {@link #uninstallPlugin},
     * so the shrinking installed list does not cause index drift.
     *
     * @param gun            the gun stack to modify
     * @param player         the player context, or {@code null}
     * @param force          {@code true} to ignore locked
     * @param returnItems    {@code true} to return plugin items
     * @param registryAccess the runtime registry view
     * @param uuids          the snapshotted uuids to uninstall
     * @return a list of results, one per uuid
     */
    private static List<UninstallResult> uninstallByUuids(
            ItemStack gun,
            @Nullable Player player,
            boolean force,
            boolean returnItems,
            RegistryAccess registryAccess,
            List<UUID> uuids
    ) {
        List<UninstallResult> results = new ArrayList<>(uuids.size());
        for (UUID uuid : uuids) {
            results.add(uninstallPlugin(gun, uuid, player, force, returnItems, registryAccess));
        }
        return results;
    }
}
