package org.yanbwe.modularshoot.plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.yanbwe.modularshoot.component.GunData;
import org.yanbwe.modularshoot.component.ModularShootDataComponents;
import org.yanbwe.modularshoot.component.PluginInstance;
import org.yanbwe.modularshoot.registry.gun.GunDefinition;
import org.yanbwe.modularshoot.registry.gun.GunRegistry;

/**
 * Tag-intersection matching and automatic plugin-category selection algorithm
 * used by the install pipeline (设计文档 §系统四 安装/拆卸 API).
 *
 * <p>A plugin is installable into a gun's category slot when the plugin's tag
 * set and the category's tag set <strong>intersect</strong> (share at least one
 * tag), and that category still has a free slot. Because a plugin may carry
 * several tags it can match several categories on the same gun; when that
 * happens the framework runs an auto-selection algorithm to pick exactly one
 * category to install into.</p>
 *
 * <h2>Tag intersection matching</h2>
 * <p>Matching is pure set intersection: {@code pluginTags ∩ typeTags ≠ ∅}.
 * Either set being empty produces no match — a plugin with no tags cannot be
 * installed on any gun, and a category with no tags can never receive any
 * plugin (设计文档 lines 382, 398). The framework logs a {@code WARN} at
 * registration time for such entries but does not block registration; this
 * service simply treats empty tag sets as non-matching.</p>
 *
 * <h2>Automatic category selection</h2>
 * <p>When a plugin matches more than one category (and each has a free slot)
 * the candidates are ordered by a three-level tie-break (设计文档 lines
 * 481-485):
 * <ol>
 *   <li><b>Primary — tag count ascending.</b> A category with fewer tags is a
 *       stricter, more precise match and is preferred over a broader one.</li>
 *   <li><b>Secondary — priority descending.</b> Higher category
 *       {@code priority} wins when tag counts tie.</li>
 *   <li><b>Tertiary — random fallback.</b> When the first two keys are
 *       identical across multiple candidates, one is chosen at random from the
 *       tied group. The {@link Random} is supplied by the caller so the
 *       selection stays deterministic in tests and server-controlled in
 *       production.</li>
 * </ol>
 *
 * <h2>Free-slot calculation</h2>
 * <p>For a category {@code typeId} on a gun, the free slot count is
 * {@code gunDefinition.slots().get(typeId) − installedCount}, where
 * {@code installedCount} is the number of already-installed plugin instances
 * whose persisted {@code installedTypeId} equals {@code typeId}. A category is
 * a candidate only when this value is positive (设计文档 line 479).</p>
 *
 * <p>All methods are static utility methods; the class is not instantiable.
 * The two core algorithms ({@link #tagsIntersect} and
 * {@link #selectPluginType}) are pure functions — the only non-deterministic
 * input is the caller-supplied {@link Random}.</p>
 */
public final class PluginMatchingService {

    private PluginMatchingService() {
    }

    /**
     * An immutable pair of a category's registry id and its
     * {@link PluginTypeDefinition}, produced by {@link #getMatchingTypes} and
     * consumed by {@link #selectPluginType}.
     *
     * <p>Carrying the id alongside the definition eliminates the reverse-lookup
     * step that was previously needed to recover the registry id from the
     * selected definition (the definition record itself does not carry its
     * id — it is supplied by the registry key).</p>
     *
     * @param id         the category's registry id
     * @param definition the category's definition record
     */
    public record TypeMatch(ResourceLocation id, PluginTypeDefinition definition) {
    }

    /**
     * Determines whether two tag sets share at least one tag (set intersection).
     *
     * <p>This is the core matching predicate: a plugin may install into a
     * category when their tag sets intersect. Either set being empty yields
     * {@code false} — empty tags never match (设计文档 lines 382, 398).</p>
     *
     * @param pluginTags the plugin's tag set; empty means no match
     * @param typeTags   the category's tag set; empty means no match
     * @return {@code true} if the two sets share at least one tag and neither
     *         is empty; {@code false} otherwise
     */
    public static boolean tagsIntersect(Set<ResourceLocation> pluginTags, Set<ResourceLocation> typeTags) {
        if (pluginTags.isEmpty() || typeTags.isEmpty()) {
            return false;
        }
        return pluginTags.stream().anyMatch(typeTags::contains);
    }

    /**
     * Selects the best category from a list of matching candidates using the
     * three-level auto-selection algorithm and returns its registry id.
     *
     * <p>Ordering (设计文档 lines 481-485):
     * <ol>
     *   <li>tag count ascending — fewer tags is a stricter match;</li>
     *   <li>priority descending — higher category priority wins on tag-count
     *       ties;</li>
     *   <li>random fallback — when several candidates share the first two keys,
     *       one is picked at random from the tied group.</li>
     * </ol>
     *
     * <p>The candidates are sorted by the first two keys; every candidate that
     * ties the first one on both tag count and priority forms the fallback
     * group, and a single member is drawn from it with the supplied
     * {@link Random}. When the best candidate is unique the fallback group has
     * exactly one element and the random draw is a no-op.</p>
     *
     * @param candidates the matching categories paired with their registry ids
     *                   (must already be filtered to those with a free slot);
     *                   may be empty
     * @param random     the source of randomness for the tertiary fallback;
     *                   only consulted when a tie exists
     * @return the registry id of the selected category, or
     *         {@code Optional.empty()} when {@code candidates} is empty
     */
    public static Optional<ResourceLocation> selectPluginType(List<TypeMatch> candidates, Random random) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<TypeMatch> sorted = candidates.stream()
                .sorted(Comparator.comparingInt((TypeMatch t) -> t.definition().tags().size())
                        .thenComparing(t -> t.definition().priority(), Comparator.reverseOrder()))
                .toList();
        TypeMatch first = sorted.get(0);
        List<TypeMatch> tied = sorted.stream()
                .filter(t -> t.definition().tags().size() == first.definition().tags().size()
                        && t.definition().priority() == first.definition().priority())
                .toList();
        return Optional.of(tied.get(random.nextInt(tied.size())).id());
    }

    /**
     * Returns the categories on a gun that match a plugin by tag intersection
     * and still have at least one free slot, each paired with its registry id.
     *
     * <p>Resolution steps:
     * <ol>
     *   <li>Read the gun's {@code gun_data} component; absent → empty list.</li>
     *   <li>Look up the {@link GunDefinition} via
     *       {@link GunRegistry#getGun(RegistryAccess, ResourceLocation)} using
     *       the gun id from the component; absent → empty list.</li>
     *   <li>Look up the {@link PluginDefinition} via
     *       {@link PluginRegistry#getPlugin(RegistryAccess, ResourceLocation)};
     *       absent → empty list.</li>
     *   <li>For every category id in {@link GunDefinition#slots()} key set,
     *       look up the {@link PluginTypeDefinition} and keep it when
     *       {@link #tagsIntersect(Set, Set)} is {@code true} <em>and</em> the
     *       category has a free slot. Each match is wrapped in a
     *       {@link TypeMatch} carrying both the id and the definition.</li>
     * </ol>
     *
     * <p>Free slot count for a category {@code typeId} is
     * {@code gunDefinition.slots().get(typeId) − installedCount}, where
     * {@code installedCount} is the number of installed plugin instances whose
     * persisted {@code installedTypeId} equals {@code typeId} (设计文档 line
     * 479). Only categories with a positive free count are returned.</p>
     *
     * @param gun           the gun item stack to inspect
     * @param pluginId      the plugin definition id to match against
     * @param registryAccess the runtime registry view (from a loaded world)
     * @return an unmodifiable-style list of {@link TypeMatch} pairs (id +
     *         definition) for matching categories with free slots; empty when
     *         the stack is not a gun, the gun/plugin definition is missing, or
     *         no category matches
     */
    public static List<TypeMatch> getMatchingTypes(ItemStack gun, ResourceLocation pluginId, RegistryAccess registryAccess) {
        GunData gunData = gun.get(ModularShootDataComponents.GUN_DATA.get());
        if (gunData == null) {
            return List.of();
        }
        Optional<GunDefinition> gunDef = GunRegistry.getGun(registryAccess, gunData.gunId());
        if (gunDef.isEmpty()) {
            return List.of();
        }
        Optional<PluginDefinition> pluginDef = PluginRegistry.getPlugin(registryAccess, pluginId);
        if (pluginDef.isEmpty()) {
            return List.of();
        }
        Set<ResourceLocation> pluginTags = new HashSet<>(pluginDef.get().tags());
        List<PluginInstance> installed = gunData.installedPlugins();
        List<TypeMatch> result = new ArrayList<>();
        for (ResourceLocation typeId : gunDef.get().slots().keySet()) {
            Optional<PluginTypeDefinition> typeDef = PluginTypeRegistry.getPluginType(registryAccess, typeId);
            if (typeDef.isEmpty()) {
                continue;
            }
            Set<ResourceLocation> typeTags = new HashSet<>(typeDef.get().tags());
            if (tagsIntersect(pluginTags, typeTags) && hasFreeSlot(gunDef.get(), installed, typeId)) {
                result.add(new TypeMatch(typeId, typeDef.get()));
            }
        }
        return result;
    }

    /**
     * Checks whether a category on a gun still has at least one free slot.
     *
     * <p>Free = capacity − installed, where capacity is the slot count from
     * {@link GunDefinition#slots()} and installed is the number of plugin
     * instances whose {@code installedTypeId} equals {@code typeId}.</p>
     *
     * @param gunDef   the gun definition carrying the slot configuration
     * @param installed the gun's currently installed plugin instances
     * @param typeId   the category id to check
     * @return {@code true} when the installed count is below the capacity
     */
    private static boolean hasFreeSlot(GunDefinition gunDef, List<PluginInstance> installed, ResourceLocation typeId) {
        int capacity = gunDef.slots().getOrDefault(typeId, 0);
        return countInstalled(installed, typeId) < capacity;
    }

    /**
     * Counts how many installed plugin instances belong to a given category.
     *
     * @param installed the gun's installed plugin instances
     * @param typeId   the category id to count against
     * @return the number of instances whose {@code installedTypeId} equals
     *         {@code typeId}
     */
    private static long countInstalled(List<PluginInstance> installed, ResourceLocation typeId) {
        return installed.stream()
                .filter(p -> p.installedTypeId().equals(typeId))
                .count();
    }
}
