package org.yanbwe.modularshoot.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginModifier;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Post-load validation utility for the {@code modularshoot:plugins}
 * datapack registry.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link PluginDefinition} entries, this class performs a secondary
 * validation pass: it checks that each entry's {@code tags} list is
 * non-empty so the plugin can match categories via tag intersection, and
 * that each modifier's {@code operation} is legal
 * (设计文档 §插件数据包 JSON and §数据包JSON加载失败错误处理).</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When {@code tags} is empty, the entry is <strong>still considered
 * registered</strong> — it is not removed from the {@code plugins}
 * registry. Instead, the validation returns a {@link PluginValidationResult}
 * carrying a warning marker (设计文档 §插件数据包 JSON). The plugin simply
 * cannot match any category until tags are added.</p>
 *
 * <p>This class is a pure validator: it returns
 * {@link PluginValidationResult} objects and never logs directly. The
 * {@code DatapackErrorHandler} (子任务08) consumes the returned results to
 * emit the actual {@code WARN}/{@code ERROR} log lines, keeping validation
 * and logging concerns separated.</p>
 *
 * <p>All query methods take a {@link RegistryAccess} so the caller supplies
 * the correct runtime view (the registry is empty on the main menu — 设计文档
 * §加载顺序). Each method is under 50 lines (设计文档 §函数&lt;50行). The
 * class is not instantiable.</p>
 *
 * @see PluginDefinition
 * @see PluginJsonCodec
 * @see PluginValidationResult
 * @see ModularShootRegistries#PLUGINS_KEY
 */
public final class PluginDatapackLoader {
    private PluginDatapackLoader() {
    }

    /**
     * Validates a single registered {@link PluginDefinition}.
     *
     * <p>This is a post-load validation: it assumes the definition has
     * already been parsed and registered. The entry is never rejected —
     * when {@code tags} is empty, a {@link PluginValidationResult} with a
     * warning marker is returned so the caller (e.g.
     * {@code DatapackErrorHandler}) can log a {@code WARN}. When a
     * modifier's {@code operation} is {@code null}, a fatal error is
     * recorded.</p>
     *
     * @param pluginId   the plugin id (registry key path from
     *                   {@code plugins/<plugin_id>.json}); used in messages
     *                   for traceability
     * @param definition the parsed definition to validate
     * @return a {@link PluginValidationResult} carrying any warnings (empty
     *         tags) and/or errors (null modifier operation)
     */
    public static PluginValidationResult validateLoadedPlugin(
            ResourceLocation pluginId, PluginDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validateTags(pluginId, definition, warnings);
        validateModifiers(pluginId, definition, errors);
        return PluginValidationResult.of(errors, warnings);
    }

    /**
     * Validates a batch of plugin entries.
     *
     * <p>Each entry is validated independently; one invalid entry does not
     * abort the batch. The returned map preserves the input keys and is
     * unmodifiable.</p>
     *
     * @param entries the plugin id to {@link PluginDefinition} mapping
     * @return an unmodifiable map of plugin id to
     *         {@link PluginValidationResult}
     */
    public static Map<ResourceLocation, PluginValidationResult> validateEntries(
            Map<ResourceLocation, PluginDefinition> entries) {
        Map<ResourceLocation, PluginValidationResult> results = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, PluginDefinition> entry : entries.entrySet()) {
            results.put(entry.getKey(), validateLoadedPlugin(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(results);
    }

    /**
     * Validates every loaded plugin in the {@code modularshoot:plugins}
     * registry.
     *
     * <p>Convenience that extracts all entries from the registry via the
     * provided {@link RegistryAccess} and delegates to
     * {@link #validateEntries(Map)}. Returns an empty map when the registry
     * is absent (e.g. on the main menu).</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return an unmodifiable map of plugin id to
     *         {@link PluginValidationResult}; empty when the registry is
     *         absent
     */
    public static Map<ResourceLocation, PluginValidationResult> validateLoadedPlugins(
            RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.PLUGINS_KEY)
                .map(PluginDatapackLoader::collectEntries)
                .map(PluginDatapackLoader::validateEntries)
                .orElse(Map.of());
    }

    // ──────────────── Private check helpers ────────────────

    /**
     * Collects all entries from a {@code plugins} registry into an
     * unmodifiable map keyed by plugin id.
     *
     * @param registry the {@code plugins} registry
     * @return an unmodifiable map of plugin id to definition
     */
    private static Map<ResourceLocation, PluginDefinition> collectEntries(
            Registry<PluginDefinition> registry) {
        Map<ResourceLocation, PluginDefinition> map = new LinkedHashMap<>();
        for (var entry : registry.entrySet()) {
            map.put(entry.getKey().location(), entry.getValue());
        }
        return Map.copyOf(map);
    }

    /**
     * Flags an empty {@code tags} list as a non-fatal warning.
     *
     * @param pluginId   the plugin id, used in the warning message
     * @param definition the definition whose tags are checked
     * @param warnings   the accumulator for warning messages
     */
    private static void validateTags(
            ResourceLocation pluginId, PluginDefinition definition, List<String> warnings) {
        if (definition.tags().isEmpty()) {
            warnings.add("Plugin '" + pluginId + "' has an empty 'tags' list; "
                    + "it cannot match any category and cannot be installed.");
        }
    }

    /**
     * Flags any modifier whose {@code operation} is {@code null} as a fatal
     * error.
     *
     * <p>The {@link PluginModifier.Operation} enum only contains the three
     * legal values ({@code add}/{@code multiply}/{@code multiply_total}),
     * so a non-null operation is always valid; only {@code null} (possible
     * when a definition is constructed directly in Java) is rejected. The
     * {@link PluginDefinition#CODEC} already enforces valid operations at
     * parse time via {@link PluginModifier.Operation}'s
     * {@code StringRepresentable} codec.</p>
     *
     * @param pluginId   the plugin id, used in the error message
     * @param definition the definition whose modifiers are checked
     * @param errors     the accumulator for error messages
     */
    private static void validateModifiers(
            ResourceLocation pluginId, PluginDefinition definition, List<String> errors) {
        for (PluginModifier modifier : definition.modifiers()) {
            if (modifier.operation() == null) {
                errors.add("Plugin '" + pluginId + "': modifier for attribute '"
                        + modifier.attribute() + "' has null operation; "
                        + "expected add/multiply/multiply_total.");
            }
        }
    }
}
