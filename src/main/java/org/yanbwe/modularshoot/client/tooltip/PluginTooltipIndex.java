package org.yanbwe.modularshoot.client.tooltip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularshoot.plugin.PluginRegistry;
import org.yanbwe.modularshoot.plugin.PluginTypeRegistry;

/**
 * Reverse index over the plugin / plugin-type registries for
 * {@link PluginTooltipBuilder}.
 *
 * <p>Building a plugin tooltip currently scans the whole plugin registry for the
 * mutual-exclusion line ({@code exclusiveGroup -> peer plugin ids}) and the
 * whole plugin-type registry for each tag match ({@code tag -> matching type
 * ids}) on every frame. This class flips those lookups into reverse maps built
 * once per registry version:
 * <ul>
 *   <li>{@code exclusiveGroup -> List<pluginId>} — plugins sharing a group;</li>
 *   <li>{@code tag -> List<pluginTypeId>} — plugin types carrying a tag.</li>
 * </ul>
 * After the one-time build, both queries are O(1) map lookups with no registry
 * scanning.</p>
 *
 * <p><b>Invalidation:</b> the index is rebuilt lazily whenever the registry
 * version (derived from the {@link net.minecraft.core.Registry} instances via
 * {@link TooltipCacheKey#registryVersion}) changes, i.e. after a datapack
 * reload. A stale registry's results are never served.</p>
 */
public final class PluginTooltipIndex {

    private int indexedRegistryVersion = -1;
    private Map<String, List<ResourceLocation>> exclusiveGroups = Map.of();
    private Map<ResourceLocation, List<ResourceLocation>> tagToTypeIds = Map.of();

    /**
     * Returns every plugin id sharing the given exclusive group, without
     * scanning the registry on repeat queries.
     *
     * @param access the runtime registry view
     * @param group  the exclusive-group id
     * @return an immutable list of plugin ids in the group; empty when none
     */
    public List<ResourceLocation> pluginIdsByExclusiveGroup(RegistryAccess access, String group) {
        ensureIndexed(access);
        return exclusiveGroups.getOrDefault(group, List.of());
    }

    /**
     * Returns every plugin-type id whose tags include the given tag, without
     * scanning the registry on repeat queries.
     *
     * @param access the runtime registry view
     * @param tag    the plugin tag id
     * @return an immutable list of plugin-type ids carrying the tag; empty when none
     */
    public List<ResourceLocation> pluginTypeIdsByTag(RegistryAccess access, ResourceLocation tag) {
        ensureIndexed(access);
        return tagToTypeIds.getOrDefault(tag, List.of());
    }

    /**
     * Rebuilds both reverse maps from the current registries when the registry
     * version has changed since the last build.
     *
     * @param access the runtime registry view
     */
    private void ensureIndexed(RegistryAccess access) {
        int version = TooltipCacheKey.registryVersion(access);
        if (version == indexedRegistryVersion) {
            return;
        }
        Map<String, List<ResourceLocation>> groups = new HashMap<>();
        for (ResourceLocation pluginId : PluginRegistry.getAllPluginIds(access)) {
            PluginRegistry.getPlugin(access, pluginId).ifPresent(def ->
                    def.exclusiveGroup().ifPresent(group ->
                            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(pluginId)));
        }
        // Keep id ordering deterministic (registration order of the id set).
        Map<ResourceLocation, List<ResourceLocation>> tags = new HashMap<>();
        for (ResourceLocation typeId : PluginTypeRegistry.getAllPluginTypeIds(access)) {
            PluginTypeRegistry.getPluginType(access, typeId).ifPresent(def -> {
                for (ResourceLocation tag : def.tags()) {
                    tags.computeIfAbsent(tag, k -> new ArrayList<>()).add(typeId);
                }
            });
        }
        this.exclusiveGroups = freezeGroups(groups);
        this.tagToTypeIds = freezeTags(tags);
        this.indexedRegistryVersion = version;
    }

    private static Map<String, List<ResourceLocation>> freezeGroups(
            Map<String, List<ResourceLocation>> src) {
        Map<String, List<ResourceLocation>> out = new HashMap<>(src.size());
        src.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    private static Map<ResourceLocation, List<ResourceLocation>> freezeTags(
            Map<ResourceLocation, List<ResourceLocation>> src) {
        Map<ResourceLocation, List<ResourceLocation>> out = new HashMap<>(src.size());
        src.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }
}
