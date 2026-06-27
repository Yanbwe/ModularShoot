package org.yanbwe.modularshoot.datapack;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.plugin.PluginTypeDefinition;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;

/**
 * Post-load validation utility for the
 * {@code modularshoot:plugin_types} datapack registry.
 *
 * <p>After the vanilla datapack registry pipeline has parsed and registered
 * {@link PluginTypeDefinition} entries, this class performs a secondary
 * validation pass: it checks that each entry's {@code tags} list is
 * non-empty so the category can match plugins via tag intersection.</p>
 *
 * <h2>Degradation policy</h2>
 * <p>When {@code tags} is empty, the entry is <strong>still considered
 * registered</strong> — it is not removed from the {@code plugin_types}
 * registry. Instead, the validation returns a {@link TypeValidation}
 * carrying a warning marker and emits a {@code WARN} log line
 * (设计文档 line 2200). The category simply cannot match any plugin until
 * tags are added. This mirrors the {@link AttributeMetaDatapackLoader}
 * degradation pattern.</p>
 *
 * <p>All query methods take a {@link RegistryAccess} so the caller supplies
 * the correct runtime view (the registry is empty on the main menu — 设计文档
 * §加载顺序). The class is not instantiable.</p>
 *
 * @see PluginTypeDefinition
 * @see PluginTypeJsonCodec
 * @see ModularShootRegistries#PLUGIN_TYPES_KEY
 */
public final class PluginTypeDatapackLoader {

    private PluginTypeDatapackLoader() {
    }

    /**
     * Validates that a {@link PluginTypeDefinition} entry has a non-empty
     * {@code tags} list.
     *
     * <p>This is a post-load validation: it assumes the definition has
     * already been parsed and registered. The entry is never rejected —
     * when {@code tags} is empty, a {@link TypeValidation} with a warning
     * marker is returned and a {@code WARN} is logged so operators can
     * identify categories that cannot match any plugin
     * (设计文档 line 2200).</p>
     *
     * @param typeId     the plugin type id (registry key path from
     *                   {@code plugin_types/<type_id>.json}); used in the
     *                   warning message for traceability
     * @param definition the parsed definition to validate
     * @return a {@link TypeValidation} that is either {@code ok} (tags
     *         present) or {@code emptyTags} (tags empty, with a warning)
     */
    public static TypeValidation validateEntry(
            ResourceLocation typeId, PluginTypeDefinition definition) {
        if (PluginTypeJsonCodec.hasEmptyTags(definition)) {
            final String warning = buildEmptyTagsWarning(typeId);
            DatapackErrorHandler.logReferenceWarning(typeId, warning);
            return TypeValidation.emptyTags(definition, warning);
        }
        return TypeValidation.ok(definition);
    }

    /**
     * Validates {@code tags} for a batch of plugin type entries.
     *
     * <p>Each entry is validated independently; one empty-tags entry does
     * not abort the batch. The returned map preserves the input keys and is
     * unmodifiable.</p>
     *
     * @param entries the type id to {@link PluginTypeDefinition} mapping
     * @return an unmodifiable map of type id to {@link TypeValidation}
     */
    public static Map<ResourceLocation, TypeValidation> validateEntries(
            Map<ResourceLocation, PluginTypeDefinition> entries) {
        return entries.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> validateEntry(entry.getKey(), entry.getValue())));
    }

    /**
     * Validates every loaded plugin type in the
     * {@code modularshoot:plugin_types} registry.
     *
     * <p>Convenience that extracts all entries from the registry via the
     * provided {@link RegistryAccess} and delegates to
     * {@link #validateEntries(Map)}. Returns an empty map when the registry
     * is absent (e.g. on the main menu).</p>
     *
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return an unmodifiable map of type id to {@link TypeValidation};
     *         empty when the registry is absent
     */
    public static Map<ResourceLocation, TypeValidation> validateLoadedTypes(
            RegistryAccess registryAccess) {
        return registryAccess.registry(ModularShootRegistries.PLUGIN_TYPES_KEY)
                .map(PluginTypeDatapackLoader::collectEntries)
                .map(PluginTypeDatapackLoader::validateEntries)
                .orElse(Map.of());
    }

    /**
     * Collects all entries from a {@code plugin_types} registry into an
     * unmodifiable map keyed by type id.
     *
     * @param registry the {@code plugin_types} registry
     * @return an unmodifiable map of type id to definition
     */
    private static Map<ResourceLocation, PluginTypeDefinition> collectEntries(
            Registry<PluginTypeDefinition> registry) {
        return registry.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> entry.getKey().location(),
                        Map.Entry::getValue));
    }

    /**
     * Builds the warning message for an empty-tags plugin type.
     *
     * @param typeId the entry's type id
     * @return the warning text
     */
    private static String buildEmptyTagsWarning(ResourceLocation typeId) {
        return "Plugin type '" + typeId + "' has an empty 'tags' list; "
                + "it cannot match any plugin (设计文档 line 2200).";
    }

    /**
     * Result of a post-load validation for one
     * {@link PluginTypeDefinition}.
     *
     * <p>Always carries the original {@code definition} so the caller can
     * proceed with registration regardless of the tags status. The
     * {@code tagsPresent} flag and optional {@code warning} tell the caller
     * whether the category can match plugins.</p>
     *
     * @param definition  the validated definition (never {@code null})
     * @param tagsPresent {@code true} if {@code definition.tags()} is
     *                    non-empty
     * @param warning     the warning message when {@code tagsPresent} is
     *                    {@code false}; empty when tags are present
     */
    public record TypeValidation(
            PluginTypeDefinition definition,
            boolean tagsPresent,
            Optional<String> warning) {

        /**
         * Factory for a successful validation: tags is non-empty.
         *
         * @param definition the validated definition
         * @return a {@link TypeValidation} with {@code tagsPresent = true}
         *         and no warning
         */
        public static TypeValidation ok(PluginTypeDefinition definition) {
            return new TypeValidation(definition, true, Optional.empty());
        }

        /**
         * Factory for a warning validation: tags is empty.
         *
         * @param definition the validated definition (still registered)
         * @param warning    the human-readable warning message
         * @return a {@link TypeValidation} with {@code tagsPresent = false}
         *         and the given warning
         */
        public static TypeValidation emptyTags(PluginTypeDefinition definition, String warning) {
            return new TypeValidation(definition, false, Optional.of(warning));
        }
    }
}
