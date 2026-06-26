package org.yanbwe.modularshoot.degradation;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginRegistry;

/**
 * Centralised handler for the <em>plugin definition missing</em> degradation
 * path (设计文档 §插件 pluginId 失效降级, lines 2325-2330).
 *
 * <p>When a gun carries a {@link PluginInstance} whose {@code pluginId} no
 * longer resolves to a registered {@code PluginDefinition} in the
 * {@code modularshoot:plugins} registry &mdash; e.g. the defining datapack was
 * removed or the id was renamed &mdash; the framework must <b>degrade
 * gracefully</b>:</p>
 * <ul>
 *   <li>the gun itself keeps shooting normally (the gun definition is
 *       independent of plugin definitions);</li>
 *   <li>the degraded plugin's attribute modifiers are <b>not</b> mounted
 *       (there is no definition to source modifier data from);</li>
 *   <li>the degraded plugin's trait overrides do <b>not</b> participate in
 *       boolean trait merging;</li>
 *   <li>the tooltip plugin bar shows a grey
 *       {@code "[失效插件] <path>"} line with no brief/description;</li>
 *   <li>the plugin can still be uninstalled by its stable instance uuid, but
 *       {@code returnItems = true} does <b>not</b> return an item (the
 *       definition is gone, so the stack is destroyed instead).</li>
 * </ul>
 *
 * <p>This class is the single source of truth for the "is this plugin
 * degraded?" decision. The attribute-modifier service, trait-merge service
 * and uninstall service all delegate here so the predicate lives in one
 * place (设计文档 §单一职责).</p>
 *
 * <p>All methods are static utility methods; the class is not
 * instantiable.</p>
 */
public final class PluginDegradationHandler {

    private PluginDegradationHandler() {
    }

    /**
     * Checks whether a plugin instance's definition is missing from the
     * {@code modularshoot:plugins} registry.
     *
     * <p>A return of {@code true} marks the instance as <b>degraded</b>: it
     * must be skipped by modifier mounting and trait merging, rendered with
     * {@link #getDegradedPluginName(PluginInstance)} in the tooltip, and
     * denied item return on uninstall.</p>
     *
     * @param instance       the installed plugin instance to check; must not
     *                       be {@code null}
     * @param registryAccess the runtime registry view (from a loaded world);
     *                       must not be {@code null}
     * @return {@code true} when the {@code pluginId} does not resolve to a
     *         registered definition (degraded); {@code false} when the
     *         definition is present
     */
    public static boolean isPluginDefinitionMissing(PluginInstance instance, RegistryAccess registryAccess) {
        return PluginRegistry.getPlugin(registryAccess, instance.pluginId()).isEmpty();
    }

    /**
     * Builds the grey tooltip display name for a degraded plugin instance.
     *
     * <p>The format is {@code "[失效插件] <path>"} where {@code <path>} is the
     * path segment of the plugin id (e.g. for {@code modularshoot:rapid_barrel}
     * the path is {@code rapid_barrel}). The whole line is styled grey and
     * carries no brief or description, matching the design spec for the
     * plugin tooltip bar (设计文档 §插件栏降级显示).</p>
     *
     * @param instance the degraded plugin instance; must not be {@code null}
     * @return a grey {@link Component} rendering {@code "[失效插件] <path>"}
     */
    public static Component getDegradedPluginName(PluginInstance instance) {
        String path = instance.pluginId().getPath();
        return Component.literal("[失效插件] " + path).withStyle(ChatFormatting.GRAY);
    }

    /**
     * Filters an installed-plugin list, keeping only instances whose
     * definition still exists in the {@code modularshoot:plugins} registry.
     *
     * <p>Used by the attribute-modifier service and the trait-merge service
     * to exclude degraded plugins from their computations. The returned list
     * preserves the original install order (the stream filter is stable)
     * so downstream priority sorting remains correct.</p>
     *
     * @param plugins        the installed plugin instances in install order;
     *                       must not be {@code null}
     * @param registryAccess the runtime registry view; must not be
     *                       {@code null}
     * @return an immutable list containing only the instances whose
     *         definition is present; empty when every instance is degraded
     */
    public static List<PluginInstance> filterValidPlugins(
            List<PluginInstance> plugins, RegistryAccess registryAccess) {
        return plugins.stream()
                .filter(p -> !isPluginDefinitionMissing(p, registryAccess))
                .toList();
    }
}
