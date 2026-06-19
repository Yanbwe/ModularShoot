package org.yanbwe.modularshoot.plugin;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable result of a single plugin uninstall operation.
 *
 * <p>Returned by every method in {@link PluginUninstallService}. The
 * {@code success} flag tells the caller whether the plugin was actually
 * removed; a {@code false} value means the plugin was skipped for one of
 * the following reasons:</p>
 * <ul>
 *   <li>the stack is not a {@code modularshoot:gun} item or carries no
 *       {@code gun_data} component;</li>
 *   <li>no installed plugin matches the given {@code instanceUuid};</li>
 *   <li>the plugin is {@code locked} and {@code force} was not
 *       requested;</li>
 *   <li>a listener cancelled the {@code PrePluginUninstallEvent}.</li>
 * </ul>
 *
 * <p>When {@code success} is {@code false} the {@code pluginId} may be
 * {@code null} (e.g. when the plugin could not be found at all). The
 * {@code instanceUuid} is {@code null} only for the no-candidate case of
 * {@link PluginUninstallService#uninstallRandomPlugin}; in all other cases
 * it echoes the uuid that was submitted.</p>
 *
 * @param success      whether the plugin was successfully uninstalled
 * @param pluginId     the registry id of the plugin that was (or was attempted
 *                     to be) uninstalled, or {@code null} when the plugin could
 *                     not be located
 * @param instanceUuid the instance uuid of the plugin that was (or was
 *                     attempted to be) uninstalled, or {@code null} for the
 *                     no-candidate random-uninstall case
 */
public record UninstallResult(
        boolean success,
        @Nullable ResourceLocation pluginId,
        @Nullable UUID instanceUuid
) {
}
