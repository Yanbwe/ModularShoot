package org.yanbwe.modularshoot.datapack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.degradation.AttributeBindsDegradationHandler;
import org.yanbwe.modularshoot.plugin.PluginDefinition;
import org.yanbwe.modularshoot.plugin.PluginModifier;
import org.yanbwe.modularshoot.registry.ModularShootRegistries;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.variant.VariantDefinition;

/**
 * Post-reload cross-reference validator for the framework datapack tables
 * (设计文档 §数据包JSON加载失败错误处理, D1 修复).
 *
 * <p>Each framework table can reference other tables (a gun's {@code stats}
 * keys reference {@code attribute_meta} entries) and vanilla registries (a
 * plugin's {@code modifiers.attribute} references the vanilla
 * {@code ATTRIBUTE} registry). A typo in such an id registered fine before:
 * the entry was accepted but the reference silently resolved to nothing at
 * runtime — zero feedback to the datapack author. This class turns every
 * dangling reference into an explicit {@code WARN} via
 * {@link DatapackErrorHandler#logReferenceWarning(ResourceLocation, String)}.</p>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li>Pure data style, mirroring {@link GunDatapackLoader#validateGuns}:
 *       the caller passes the already-loaded entries map; the entry stays
 *       registered regardless of the outcome (degradation is deferred).</li>
 *   <li>Empty entries maps return immediately (注册表缺失时静默 — a missing
 *       framework registry is a legitimate state, not a cascade of warnings).</li>
 *   <li>{@code findUnmatchedTags} is a package-private pure function so the
 *       tags-intersection rule is unit-testable without a
 *       {@link RegistryAccess}.</li>
 * </ul>
 *
 * @see DatapackErrorHandler
 * @see GunDatapackLoader
 */
public final class CrossReferenceValidator {

    private CrossReferenceValidator() {
    }

    /**
     * Validates every cross-table reference carried by a gun definition.
     *
     * <p>Checks performed (all emit {@code WARN} via
     * {@link DatapackErrorHandler#logReferenceWarning}):</p>
     * <ul>
     *   <li>{@code stats} keys exist in the {@code attribute_meta} table</li>
     *   <li>{@code traits} keys exist in the {@code traits} table</li>
     *   <li>{@code slots} keys exist in the {@code plugin_types} table</li>
     *   <li>{@code variants} keys exist in the {@code variants} table</li>
     *   <li>{@code sounds} values are registered sound events (音效事件须由
     *       模组 DeferredRegister 注册，JSON 只做绑定)</li>
     * </ul>
     *
     * @param access the reloaded registry access
     * @param guns   the loaded gun id to {@link GunDefinition} entries
     */
    public static void validateGuns(
            RegistryAccess access, Map<ResourceLocation, GunDefinition> guns) {
        if (guns.isEmpty()) {
            return;  // 注册表缺失时静默
        }
        Set<ResourceLocation> metaKeys =
                registryKeys(access, ModularShootRegistries.ATTRIBUTE_META_KEY);
        Set<ResourceLocation> traitKeys = registryKeys(access, ModularShootRegistries.TRAITS_KEY);
        Set<ResourceLocation> typeKeys = registryKeys(access, ModularShootRegistries.PLUGIN_TYPES_KEY);
        Set<ResourceLocation> variantKeys = registryKeys(access, ModularShootRegistries.VARIANTS_KEY);
        for (Map.Entry<ResourceLocation, GunDefinition> entry : guns.entrySet()) {
            ResourceLocation gunId = entry.getKey();
            GunDefinition gun = entry.getValue();
            checkTableKeys(gunId, "stats", "attribute_meta", gun.stats().keySet(), metaKeys);
            checkTableKeys(gunId, "traits", "traits", gun.traits().keySet(), traitKeys);
            checkTableKeys(gunId, "slots", "plugin_types", gun.slots().keySet(), typeKeys);
            checkTableKeys(gunId, "variants", "variants", gun.variants().keySet(), variantKeys);
            checkSounds(gunId, gun);
        }
    }

    /**
     * Validates every cross-table reference carried by a plugin definition.
     *
     * <p>Checks performed (all emit {@code WARN}):</p>
     * <ul>
     *   <li>{@code modifiers.attribute} resolves to a registered vanilla
     *       {@code ATTRIBUTE} id (bare keys default to the
     *       {@code modularshoot} namespace, the same rule as
     *       {@code AttributeModifierService.resolveAttributeHolder})</li>
     *   <li>{@code traits} keys exist in the {@code traits} table</li>
     *   <li>{@code adds_variants} keys exist in the {@code variants} table</li>
     *   <li>{@code tags} intersect at least one {@code plugin_types} tags set
     *       (无交集 → 该插件永远装不上任何枪，兑现文档承诺的 WARN)</li>
     * </ul>
     *
     * @param access  the reloaded registry access
     * @param plugins the loaded plugin id to {@link PluginDefinition} entries
     */
    public static void validatePlugins(
            RegistryAccess access, Map<ResourceLocation, PluginDefinition> plugins) {
        if (plugins.isEmpty()) {
            return;
        }
        Set<ResourceLocation> traitKeys = registryKeys(access, ModularShootRegistries.TRAITS_KEY);
        Set<ResourceLocation> variantKeys = registryKeys(access, ModularShootRegistries.VARIANTS_KEY);
        Map<ResourceLocation, Set<String>> typeTagSets = collectTypeTagSets(access);
        for (Map.Entry<ResourceLocation, PluginDefinition> entry : plugins.entrySet()) {
            ResourceLocation pluginId = entry.getKey();
            PluginDefinition plugin = entry.getValue();
            checkModifierAttributes(pluginId, plugin);
            checkTableKeys(pluginId, "traits", "traits", plugin.traits().keySet(), traitKeys);
            checkTableKeys(pluginId, "adds_variants", "variants",
                    plugin.addsVariants().keySet(), variantKeys);
            checkPluginTags(pluginId, plugin, typeTagSets);
        }
    }

    /**
     * Validates every cross-table reference carried by a variant definition.
     *
     * <p>Checks performed (all emit {@code WARN}):</p>
     * <ul>
     *   <li>{@code damage_type} (when present) is registered in the vanilla
     *       {@code DAMAGE_TYPE} registry</li>
     *   <li>{@code traits} keys exist in the {@code traits} table</li>
     *   <li>{@code stats} keys exist in the {@code attribute_meta} table</li>
     * </ul>
     *
     * @param access   the reloaded registry access
     * @param variants the loaded variant id to {@link VariantDefinition} entries
     */
    public static void validateVariants(
            RegistryAccess access, Map<ResourceLocation, VariantDefinition> variants) {
        if (variants.isEmpty()) {
            return;
        }
        Set<ResourceLocation> traitKeys = registryKeys(access, ModularShootRegistries.TRAITS_KEY);
        Set<ResourceLocation> metaKeys =
                registryKeys(access, ModularShootRegistries.ATTRIBUTE_META_KEY);
        Registry<DamageType> damageTypes = access.registryOrThrow(Registries.DAMAGE_TYPE);
        for (Map.Entry<ResourceLocation, VariantDefinition> entry : variants.entrySet()) {
            ResourceLocation variantId = entry.getKey();
            VariantDefinition variant = entry.getValue();
            checkTableKeys(variantId, "traits", "traits", variant.traits().keySet(), traitKeys);
            checkTableKeys(variantId, "stats", "attribute_meta",
                    variant.stats().keySet(), metaKeys);
            variant.damageType().ifPresent(damageType -> {
                if (!damageTypes.containsKey(damageType)) {
                    DatapackErrorHandler.logReferenceWarning(variantId,
                            "damage_type '" + damageType
                                    + "' is not registered in the vanilla damage type registry");
                }
            });
        }
    }

    /**
     * Returns the tags from {@code pluginTags} that do not appear in any of
     * the per-type tag sets.
     *
     * <p>Pure function (no {@link RegistryAccess}) so the intersection rule
     * is unit-testable. Empty {@code pluginTags} yields an empty list; an
     * empty {@code typeTagSets} yields every plugin tag unchanged.</p>
     *
     * @param pluginTags   the plugin's declared tags (string form, e.g.
     *                     {@code "modularshoot:barrel"})
     * @param typeTagSets  plugin type id to that type's tag set; tags in the
     *                     same string form
     * @return the unmatched tags in the input set's iteration order; empty
     *         when every tag appears in at least one type's tag set
     */
    static List<String> findUnmatchedTags(
            Set<String> pluginTags, Map<ResourceLocation, Set<String>> typeTagSets) {
        if (pluginTags.isEmpty()) {
            return List.of();
        }
        Set<String> allTypeTags = typeTagSets.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());
        return pluginTags.stream()
                .filter(tag -> !allTypeTags.contains(tag))
                .toList();
    }

    /**
     * Collects the key set of a framework registry; empty when the registry
     * is absent.
     *
     * @param access the registry access
     * @param key    the registry key
     * @param <T>    the registry value type
     * @return the registry's id set, or {@link Set#of()} when absent
     */
    private static <T> Set<ResourceLocation> registryKeys(
            RegistryAccess access, ResourceKey<Registry<T>> key) {
        return access.registry(key).map(Registry::keySet).orElse(Set.of());
    }

    /**
     * Emits a {@code WARN} per map key that is not registered in its target
     * table.
     *
     * @param id         the entry id carrying the reference
     * @param field      the referencing field name (e.g. {@code "stats"})
     * @param table      the target table name (e.g. {@code "attribute_meta"})
     * @param keys       the referencing keys
     * @param registered the target table's registered id set
     */
    private static void checkTableKeys(
            ResourceLocation id, String field, String table,
            Set<ResourceLocation> keys, Set<ResourceLocation> registered) {
        for (ResourceLocation key : keys) {
            if (!registered.contains(key)) {
                DatapackErrorHandler.logReferenceWarning(id,
                        field + " key '" + key + "' is not registered in the "
                                + table + " table");
            }
        }
    }

    /**
     * Emits a {@code WARN} per sound binding whose event id is not a
     * registered sound event.
     *
     * <p>Sound events must be registered by a mod's {@code DeferredRegister}
     * (plus {@code sounds.json}); the datapack JSON only binds them, so an
     * unregistered id is a datapack-author typo worth surfacing.</p>
     *
     * @param gunId the gun id carrying the bindings
     * @param gun   the gun definition to check
     */
    private static void checkSounds(ResourceLocation gunId, GunDefinition gun) {
        for (Map.Entry<String, ResourceLocation> binding : gun.sounds().entrySet()) {
            ResourceLocation event = binding.getValue();
            if (!BuiltInRegistries.SOUND_EVENT.containsKey(event)) {
                DatapackErrorHandler.logReferenceWarning(gunId,
                        "sound slot '" + binding.getKey()
                                + "' references unregistered sound event '" + event
                                + "'; sound events must be registered by a mod's DeferredRegister");
            }
        }
    }

    /**
     * Emits a {@code WARN} per plugin modifier whose attribute id is not
     * registered in the vanilla {@code ATTRIBUTE} registry.
     *
     * <p>Bare names default to the {@code modularshoot} namespace, the same
     * resolution rule as
     * {@code AttributeModifierService.resolveAttributeHolder}; the shared
     * registration predicate is
     * {@link AttributeBindsDegradationHandler#isAttributeRegistered}.</p>
     *
     * @param pluginId the plugin id carrying the modifiers
     * @param plugin   the plugin definition to check
     */
    private static void checkModifierAttributes(ResourceLocation pluginId, PluginDefinition plugin) {
        for (PluginModifier modifier : plugin.modifiers()) {
            ResourceLocation attrId = modifier.attribute().contains(":")
                    ? ResourceLocation.parse(modifier.attribute())
                    : ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, modifier.attribute());
            if (!AttributeBindsDegradationHandler.isAttributeRegistered(attrId)) {
                DatapackErrorHandler.logReferenceWarning(pluginId,
                        "modifier attribute '" + modifier.attribute()
                                + "' is not registered in the vanilla attribute registry");
            }
        }
    }

    /**
     * Emits a {@code WARN} when the plugin's tags intersect no plugin type's
     * tag set (the plugin can never be installed on any gun).
     *
     * @param pluginId    the plugin id carrying the tags
     * @param plugin      the plugin definition to check
     * @param typeTagSets plugin type id to tag set (string form)
     */
    private static void checkPluginTags(
            ResourceLocation pluginId, PluginDefinition plugin,
            Map<ResourceLocation, Set<String>> typeTagSets) {
        Set<String> pluginTags = plugin.tags().stream()
                .map(ResourceLocation::toString)
                .collect(Collectors.toSet());
        List<String> unmatched = findUnmatchedTags(pluginTags, typeTagSets);
        if (!unmatched.isEmpty()) {
            DatapackErrorHandler.logReferenceWarning(pluginId,
                    "tags " + unmatched + " match no plugin type's tags; "
                            + "the plugin cannot be installed on any gun");
        }
    }

    /**
     * Collects every plugin type's tag set as strings, keyed by type id.
     *
     * @param access the registry access
     * @return type id to tag-set map; empty when the table is absent
     */
    private static Map<ResourceLocation, Set<String>> collectTypeTagSets(RegistryAccess access) {
        return access.registry(ModularShootRegistries.PLUGIN_TYPES_KEY)
                .map(reg -> reg.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().location(),
                                e -> e.getValue().tags().stream()
                                        .map(ResourceLocation::toString)
                                        .collect(Collectors.toSet()))))
                .orElse(Map.of());
    }
}
