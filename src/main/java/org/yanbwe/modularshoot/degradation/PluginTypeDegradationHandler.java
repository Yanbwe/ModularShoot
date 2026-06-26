package org.yanbwe.modularshoot.degradation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.ModularShoot;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;

/**
 * Graceful-degradation handler for missing plugin type (category) definitions
 * (设计文档 §种类定义丢失降级, lines 2332-2343).
 *
 * <p>The {@code modularshoot:plugin_types} registry is datapack-driven and
 * hot-reloadable. A reload can remove a category definition while plugin
 * instances already installed into that category persist on gun stacks — their
 * {@link PluginInstance#installedTypeId()} still points at the now-missing id.
 * This handler provides the query and grouping primitives that keep the
 * framework crash-free in that situation: missing types are silently filtered
 * from candidate lists, installed plugins are retained on the gun (no
 * auto-uninstall), and tooltip rendering falls back to a grey
 * "{@code [未知种类]}" / "{@code 可安装至: 未知}" placeholder.</p>
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>No crash</b> — every method returns a safe fallback when a type
 *       definition is absent; the plugin item tooltip and the Shift-expanded
 *       tag list keep rendering normally.</li>
 *   <li><b>No auto-uninstall</b> — a missing type never removes the plugin
 *       from the gun; the instance is retained and re-grouped once the
 *       definition returns (next reload / re-query).</li>
 *   <li><b>Modifiers unaffected</b> — plugin modifiers come from the plugin
 *       definition, not the type definition, so they still participate in
 *       {@code ATTRIBUTE_MODIFIERS} calculation. This handler does not touch
 *       the modifier service.</li>
 *   <li><b>Unknown group last</b> — {@link #groupPluginsByType} places every
 *       plugin whose type is missing into a single sentinel-keyed group at
 *       the end of the returned map, so tooltip plugin bars can render it as
 *       the final grey "{@code [未知种类]}" section.</li>
 * </ul>
 *
 * <p>All methods are static utility methods; the class is not instantiable.
 * The query and filtering methods are pure functions of their arguments.</p>
 *
 * @see PluginTypeRegistry
 */
public final class PluginTypeDegradationHandler {
    private PluginTypeDegradationHandler() {
    }

    /**
     * Sentinel map key used by {@link #groupPluginsByType} for the degraded
     * "unknown type" group. Callers can test a group key against this constant
     * — or use {@link #isUnknownGroup(ResourceLocation)} — to decide whether
     * to render the group with the grey {@link #getDegradedTypeName()} label.
     *
     * <p>The path is deliberately distinctive ({@code __unknown_type__}) to
     * avoid colliding with a real registered plugin type id.</p>
     */
    public static final ResourceLocation UNKNOWN_TYPE_ID =
            ResourceLocation.fromNamespaceAndPath(ModularShoot.MODID, "__unknown_type__");

    /**
     * Checks whether the plugin type (category) definition for the given
     * installed-type id is absent from the {@code modularshoot:plugin_types}
     * registry.
     *
     * <p>A missing definition means the category was never registered or was
     * removed by a datapack reload. The installed plugin instance is retained
     * regardless; this method only reports the registry state so callers can
     * choose a degraded display path. On the main menu (empty registry) every
     * id reports as missing, which is the correct degraded state.</p>
     *
     * @param installedTypeId the category id recorded on a
     *                        {@link PluginInstance}; must not be {@code null}
     * @param registryAccess  the runtime registry view (from a loaded world);
     *                        must not be {@code null}
     * @return {@code true} when the type definition is absent (degraded);
     *         {@code false} when it is present
     */
    public static boolean isPluginTypeDefinitionMissing(
            ResourceLocation installedTypeId, RegistryAccess registryAccess) {
        Objects.requireNonNull(installedTypeId, "installedTypeId");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return PluginTypeRegistry.getPluginType(registryAccess, installedTypeId).isEmpty();
    }

    /**
     * Returns the grey "{@code [未知种类]}" component used as the display name
     * for a degraded plugin-type group (设计文档 line 2338).
     *
     * <p>This is a fresh component on every call; callers may freely append to
     * or restyle it without affecting other callers.</p>
     *
     * @return a grey {@link Component} literal reading {@code [未知种类]}
     */
    public static Component getDegradedTypeName() {
        return Component.literal("[未知种类]").withStyle(ChatFormatting.GRAY);
    }

    /**
     * Returns the grey "{@code 可安装至: 未知}" component used for a plugin
     * item's "installable into" tooltip line when every matching category is
     * missing (设计文档 line 2334).
     *
     * <p>This is a fresh component on every call.</p>
     *
     * @return a grey {@link Component} literal reading
     *         {@code 可安装至: 未知}
     */
    public static Component getDegradedInstallTarget() {
        return Component.literal("可安装至: 未知").withStyle(ChatFormatting.GRAY);
    }

    /**
     * Filters a list of candidate plugin-type ids, keeping only those whose
     * definitions are still present in the {@code modularshoot:plugin_types}
     * registry.
     *
     * <p>Used by the plugin item tooltip "可安装至" line: when the line
     * reverse-looks-up tags for each candidate category, already-lost types
     * are excluded from the result so the line never references a missing
     * category (设计文档 line 2332). When this method returns an empty list
     * the caller should render {@link #getDegradedInstallTarget()} instead
     * (设计文档 line 2334).</p>
     *
     * @param typeIds        the candidate category ids; must not be
     *                       {@code null}
     * @param registryAccess the runtime registry view; must not be
     *                       {@code null}
     * @return an unmodifiable list of category ids whose definitions exist,
     *         preserving the input order; empty when none survive
     */
    public static List<ResourceLocation> filterValidTypes(
            List<ResourceLocation> typeIds, RegistryAccess registryAccess) {
        Objects.requireNonNull(typeIds, "typeIds");
        Objects.requireNonNull(registryAccess, "registryAccess");
        return typeIds.stream()
                .filter(id -> !isPluginTypeDefinitionMissing(id, registryAccess))
                .toList();
    }

    /**
     * Groups installed plugin instances by their category, placing every
     * plugin whose category definition is missing into a single
     * sentinel-keyed "unknown type" group at the end of the returned map.
     *
     * <p>The returned map preserves insertion order: valid categories appear
     * in the order they are first encountered in {@code plugins}, and the
     * degraded group — when non-empty — is always the last entry, keyed by
     * {@link #UNKNOWN_TYPE_ID}. Tooltip plugin-bar renderers can iterate the
     * map in order and render the final group with the grey
     * {@link #getDegradedTypeName()} label (设计文档 line 2338).</p>
     *
     * <p>Because grouping is recomputed on every call, a plugin whose
     * category definition returns (e.g. after a reload) is automatically
     * placed back into its correct valid group — no persisted flag is
     * stored, so the tooltip recovers on the next re-query (设计文档 line
     * 2343).</p>
     *
     * @param plugins        the installed plugin instances to group; must not
     *                       be {@code null}
     * @param registryAccess the runtime registry view; must not be
     *                       {@code null}
     * @return an unmodifiable, insertion-ordered map from category id to an
     *         unmodifiable list of plugins in that category; the degraded
     *         group, when present, is the last entry keyed by
     *         {@link #UNKNOWN_TYPE_ID}; empty when {@code plugins} is empty
     */
    public static Map<ResourceLocation, List<PluginInstance>> groupPluginsByType(
            List<PluginInstance> plugins, RegistryAccess registryAccess) {
        Objects.requireNonNull(plugins, "plugins");
        Objects.requireNonNull(registryAccess, "registryAccess");
        Map<ResourceLocation, List<PluginInstance>> groups = new LinkedHashMap<>();
        List<PluginInstance> unknown = new ArrayList<>();
        for (PluginInstance plugin : plugins) {
            ResourceLocation typeId = plugin.installedTypeId();
            if (isPluginTypeDefinitionMissing(typeId, registryAccess)) {
                unknown.add(plugin);
            } else {
                groups.computeIfAbsent(typeId, k -> new ArrayList<>()).add(plugin);
            }
        }
        if (!unknown.isEmpty()) {
            groups.put(UNKNOWN_TYPE_ID, unknown);
        }
        Map<ResourceLocation, List<PluginInstance>> frozen = new LinkedHashMap<>();
        groups.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * Tests whether a group key returned by {@link #groupPluginsByType} is
     * the sentinel "unknown type" key, signalling that the group should be
     * rendered with the grey {@link #getDegradedTypeName()} label.
     *
     * @param typeId the group key to test; must not be {@code null}
     * @return {@code true} when {@code typeId} equals {@link #UNKNOWN_TYPE_ID}
     */
    public static boolean isUnknownGroup(ResourceLocation typeId) {
        Objects.requireNonNull(typeId, "typeId");
        return UNKNOWN_TYPE_ID.equals(typeId);
    }
}
